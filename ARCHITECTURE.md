# Architecture

How the system is put together and how a request travels through it. Every section describes code
that exists; file names are given so each claim can be checked against the source. The reasons
behind the choices are in `DECISIONS.md`.

## The whole system in one picture

```mermaid
flowchart LR
    subgraph Browser
        DASH[Dashboard<br/>React + TypeScript]
        DEMO[Demo app<br/>static site + sdk/]
    end

    subgraph Backend["Backend: one Spring Boot app (Java 21)"]
        RL[RateLimitFilter]
        CP[Control plane<br/>accounts, projects, keys,<br/>schema designer, rules]
        MIG[MigrationPlanner<br/>operations to DDL]
        ID[IdentityResolver<br/>API key + token to identity]
        QC[Query compiler<br/>URL to AST to SQL + binds]
        TT[TenantTransaction<br/>SET LOCAL ROLE + user id]
        HUB[LiveHub<br/>WebSocket subscribers]
        LS[ChangeListener<br/>LISTEN on one connection]
    end

    subgraph PG["PostgreSQL (one database)"]
        PLAT[(schema tsk_platform<br/>accounts, projects, catalog,<br/>migrations, credentials, change log)]
        PA[(schema p_ab12...<br/>project A tables + policies)]
        PB[(schema p_cd34...<br/>project B tables + policies)]
    end

    DASH -->|cookie session| RL --> CP
    CP --> MIG --> PA
    CP --> PLAT
    DEMO -->|REST + API key + user token| RL
    RL --> ID --> QC --> TT --> PA
    DEMO <-->|WebSocket| HUB
    PA -. trigger: record + NOTIFY .-> LS --> HUB
    HUB -->|read the row as each subscriber| TT
```

Two halves, and the split matters:

- **Control plane**: what a project's owner uses through the dashboard. It runs DDL and connects
  to Postgres as the owner role.
- **Data plane**: what apps built on a project use (REST and WebSocket). It connects as a login
  role with **no privileges of its own**, and can only act by switching into one project's role
  for one transaction.

## Stack

| Piece | Choice | Why |
|---|---|---|
| Backend | Java 21, Spring Boot 4.1 (Web MVC, Security, WebSocket), Maven wrapper | Blocking MVC code is easier to read and debug than reactive code; virtual threads give the concurrency. |
| Database access | `JdbcTemplate` and a hand-written SQL builder, no ORM | Tables are created at runtime by users; an ORM needs classes known at compile time. |
| Database | PostgreSQL 18 | Row-level security, transactional DDL, schemas, roles and LISTEN/NOTIFY: each hard feature leans on something Postgres already does well. |
| Platform tables | Flyway migrations (`V1` to `V6`) | The standard tool for our own tables. Project tables use our own migration engine. |
| Dashboard | React, TypeScript, Vite, TanStack Query, plain CSS | Built into the Spring Boot jar: one deploy, one URL. |
| SDK and demo | TypeScript, no dependencies; React for the demo | The demo talks only through the SDK, which is the proof of "no backend code". |
| Tests | JUnit, AssertJ, Testcontainers (a real PostgreSQL), Node's test runner for the SDK | Injection and permission tests mean something only against a real database. |
| Hosting | Render free web service, Neon free PostgreSQL, GitHub Pages, GitHub Actions | No cost and no card. The price is sleep after idle time (see README). |

Repository layout:

```
backend/     the Spring Boot app, one package per feature:
             platform/ (accounts, projects, keys)   tenancy/ (roles, the tenant transaction)
             schema/ (catalog, migrations)          query/ (the compiler)
             data/ (REST endpoints)                 auth/ (end users, tokens)
             live/ (live updates)                   common/ (errors, security, limits)
dashboard/   the React dashboard (built into the backend jar)
sdk/         the TypeScript client
demo/        the demo app (published to GitHub Pages)
scripts/     local database, demo project setup, benchmark
```

## Data model

The platform's own tables live in the schema `tsk_platform`, which no project role can read.

```mermaid
erDiagram
    account ||--o{ session : "dashboard logins"
    account ||--o{ project : owns
    project ||--o{ api_key : "anon + service"
    project ||--o{ migration : "history"
    project ||--o{ table_meta : contains
    table_meta ||--o{ column_meta : has
    table_meta ||--o{ rule : "one per operation"
    project ||--o{ end_user : "password hashes"
    end_user ||--o{ end_user_session : "refresh tokens"

    account { uuid id  text email  text password_hash }
    session { text token_hash  uuid account_id  timestamptz expires_at }
    project { uuid id  uuid account_id  text name  text schema_name  bytea jwt_secret  int schema_version }
    api_key { uuid project_id  text kind  text key_hash  text prefix }
    migration { uuid project_id  int version  jsonb operations  text sql }
    table_meta { uuid id  uuid project_id  text name  bool builtin }
    column_meta { uuid table_id  text name  text type  bool nullable  bool is_unique  text ref_table  text on_delete }
    rule { uuid table_id  text operation  text kind  text owner_column }
    end_user { uuid project_id  uuid user_id  text email  text password_hash }
    end_user_session { text token_hash  uuid user_id  timestamptz expires_at }
```

Two more tables there belong to live updates: `change_event` (the log of row changes) and
`change_floor` (how far that log has been trimmed).

`table_meta`, `column_meta` and `rule` are **the catalog**. The query compiler never trusts a name
from a request; it looks the name up in the catalog and uses the catalog's copy.

Each project has its own schema `p_<12 hex characters>` holding:

- a built-in `users` table (`id`, `email`, `created_at`): the public profile of each end user, so
  the owner's tables can reference it. Password hashes are not here;
- the tables the owner designs. Every table gets an `id uuid` primary key and `created_at`, row-level
  security forced on, a trigger that records changes and a trigger that enforces the row quota.

## Health check and deny-by-default

```mermaid
sequenceDiagram
    participant C as Client
    participant S as Spring Security filter chain
    participant A as Actuator health endpoint
    participant P as PostgreSQL

    C->>S: GET /actuator/health
    S->>A: path is on the allow list
    A->>P: SELECT 1
    A-->>C: 200 {"status":"UP"}

    C->>S: GET /anything-else
    S-->>C: 403 (never reaches a controller)
```

At startup, Flyway connects as the owner role and applies new files from
`backend/src/main/resources/db/migration` to schema `tsk_platform`.

## Two database identities

| Pool | Logs in as | Can do | Used by |
|---|---|---|---|
| owner (Spring's default `DataSource`) | the database owner | everything: platform tables, DDL | control plane |
| tenant-data (private to `TenantTransaction`) | `tsk_api` | nothing, until it switches into one project's role | data plane |

## Control plane: designing a table

```mermaid
sequenceDiagram
    participant D as Dashboard (browser)
    participant F as SessionAuthFilter
    participant SC as SchemaController
    participant MS as MigrationService
    participant MP as MigrationPlanner
    participant P as PostgreSQL (owner)

    D->>F: POST /platform/v1/projects/{id}/migrations + session cookie
    F->>P: session token hash -> account
    F->>SC: request now carries the Account
    SC->>SC: project must belong to this account (else 404)
    SC->>MS: apply(project, operations, dryRun)
    MS->>P: BEGIN, lock project row
    MS->>P: load catalog
    MS->>MP: plan(catalog, operations)
    MP-->>MS: SQL statements + resulting catalog (or a validation error)
    MS->>P: run DDL, replace catalog rows, insert history row
    MS->>P: COMMIT (or ROLLBACK for a dry run / any failure)
    MS-->>D: {version, sql, applied}
```

## Data plane: a REST call

```mermaid
sequenceDiagram
    participant C as Client app
    participant K as ApiKeyFilter
    participant DC as DataController
    participant RE as RowEndpoints
    participant RS as RowService
    participant QP as QueryParser
    participant QV as QueryValidator
    participant SB as SqlBuilder
    participant TT as TenantTransaction
    participant P as PostgreSQL (tsk_api)

    C->>K: GET /api/v1/posts?select=title,authors(name)&priority=gte.2 + apikey header
    K->>P: key hash -> project + role (owner pool)
    K->>DC: request now carries a TenantIdentity
    DC->>RE: select(identity, "posts", request)
    RE->>RS: select(identity, "posts", query parameters, count?)
    RS->>QP: parse(parameters)
    QP-->>RS: RawQuery (plain text in a structure, nothing checked yet)
    RS->>QV: select("posts", RawQuery)
    QV->>QV: look every table and column name up in the catalog (404 / 400 if absent)
    QV-->>RS: SelectQuery (holds catalog objects; request values wrapped in Param)
    RS->>SB: select(project, limits, SelectQuery)
    SB-->>RS: CompiledSql (SQL text with ? placeholders + bind list)
    RS->>TT: execute(identity, work)
    TT->>P: BEGIN
    TT->>P: SET LOCAL ROLE p_x_service, search_path, statement_timeout, tsk.user_id
    TT->>P: the compiled statement, with bind parameters
    Note over P: table grants + row-level security decide what is visible
    TT->>P: COMMIT (role and settings vanish)
    TT-->>C: JSON text straight from Postgres
```

The dashboard's data browser takes the same path from `RowService` onwards, as the service role.

### The three compiler stages

| Stage | Input -> output | Responsible for | Must never |
|---|---|---|---|
| `QueryParser` | query string -> `RawQuery` (text in a structure) | The shape of the request: `select` grammar, `column=op.value` filters, `order`, `limit`, `offset`. Size limits. | Know which tables or columns exist, or trust any text it reads. |
| `QueryValidator` | `RawQuery` -> `SelectQuery` / `WriteCommand` (trusted AST) | The trust boundary. Looks each name up in the catalog and keeps the catalog's object. Checks operators against column types, relations, and `QueryLimits`. | Let a request string into the AST as a name. Request text survives only as a `Param` value. |
| `SqlBuilder` | AST -> `CompiledSql` (SQL + bind list) | Writing SQL: keywords are literals in the class, identifiers are quoted catalog names, every value becomes `?`. | Accept a `String` from a caller, or put a `Param` value into the SQL text. |

Worked example. The request below uses the test blog schema, where `posts` has two columns that
reference `authors`, so the column is named with `!`:

```
GET /api/v1/posts?select=title,authors!author_id(name)&priority=gte.2&order=title&limit=5
```

`SqlBuilder` compiles it to this statement for project schema `p_0123456789ab` (line breaks added
here for reading; the real text is one line):

```sql
select coalesce(json_agg(row_to_json(r)), '[]'::json)::text from (
  select t0."title",
    (select row_to_json(e1) from (
      select t1."name" from "p_0123456789ab"."authors" as t1
      where t1."id" = t0."author_id") e1) as "authors"
  from "p_0123456789ab"."posts" as t0
  where t0."priority" >= cast(? as integer)
  order by t0."title", t0."id"
  limit ? offset ?) r
```

Bind parameters, in the order of the `?` placeholders: `"2"`, `5`, `0`. The first is the filter
value. It comes from the request, is sent as the text `"2"` and is cast by Postgres to `integer`.
The other two are `limit` and `offset` as integers; `offset` defaulted to 0.

Things to notice: the table aliases are `t0` (posts) and `t1` (authors), and `e1` names the inner
row; `id` is appended to `order by` so the order is total; the author comes back as a nested JSON
object because Postgres builds it in the sub-select.

## Who is asking: from headers to a Postgres role

```mermaid
flowchart LR
    K[apikey header] --> F[ApiKeyFilter]
    T[Authorization: Bearer token] --> F
    F -->|service key| S[TenantIdentity SERVICE]
    F -->|anon key, no token| A[TenantIdentity ANON]
    F -->|anon key + valid token| U[TenantIdentity USER + user id]
    F -->|anon key + bad token| X[401 invalid_token]
    S --> TT[TenantTransaction]
    A --> TT
    U --> TT
    TT -->|SET LOCAL ROLE p_x_service| P[(PostgreSQL)]
    TT -->|SET LOCAL ROLE p_x_anon| P
    TT -->|"SET LOCAL ROLE p_x_user + tsk.user_id"| P
```

Inside Postgres, every table has row-level security forced on. A rule set in the dashboard is a
policy such as `USING (owner_id = tsk.uid())` for the `p_x_user` role; `tsk.uid()` reads the
transaction-local `tsk.user_id`. The API code never adds an ownership filter itself.

Login (`POST /auth/v1/login`): `ApiKeyFilter` identifies the project, `AuthService` checks the
BCrypt hash in `tsk_platform.end_user`, then issues a JWT signed with the project's secret
(`JwtCodec`) and a single-use refresh token stored as a hash.

## Live updates: from a write to a subscriber's screen

```mermaid
sequenceDiagram
    participant W as Writer (any API request)
    participant PG as PostgreSQL
    participant L as ChangeListener (LISTEN)
    participant H as LiveHub (one thread)
    participant C as Subscriber (WebSocket)

    C->>H: auth {apikey, token} then subscribe {table}
    H->>L: start (first subscriber only)
    L->>PG: LISTEN tsk_changes
    H-->>C: subscribed {cursor}

    W->>PG: INSERT INTO notes ...
    Note over PG: trigger tsk.record_change():<br/>insert into change_event,<br/>pg_notify(table, op, row id)
    W->>PG: COMMIT
    PG-->>L: notification (only after commit)
    L->>H: queue the change
    loop each distinct identity subscribed to notes
        H->>PG: SET LOCAL ROLE ...; SELECT the row by id
        PG-->>H: the row, or nothing (row-level security)
    end
    H-->>C: event {op, id, row, eventId, cursor} (only if the row was visible)
```

The pieces, in the order a change passes through them:

| Piece | File | Job |
|---|---|---|
| Trigger | `V4__change_events.sql`, `schema/ChangeTrigger.java` | Record "row X of table T changed" and notify |
| Listener | `live/ChangeListener.java` | One connection that only listens; reconnects if it breaks |
| Hub | `live/LiveHub.java` | Who is subscribed; reads the row as each identity; sends |
| Log | `live/EventLog.java` | Replay for reconnecting clients; trimmed after 10 minutes |
| Endpoint | `live/LiveSocketHandler.java` | The JSON protocol on `/realtime/v1`, limits |

Deletes: the row is gone, so it cannot be read. The trigger keeps a copy in the log and the hub
applies the table's read rule to that copy; only the id is sent (DECISIONS.md D32).

Reconnecting: the client sends `subscribe {table, since: <last cursor>}`. The hub reads the missed
events from `change_event`, checks each against the client's identity exactly as above, and sends
them before any new live event. If it cannot know what was missed it answers `resync: true` and
the client loads the table again (DECISIONS.md D33, D34).

## The demo app: where each piece runs

```mermaid
flowchart LR
    subgraph Browser
        D[demo/ React app] --> S[sdk/ TsukuyomiClient]
    end
    GH[GitHub Pages<br/>static files only] -. serves the page .-> D
    S -->|"/auth/v1  login, refresh"| T[Tsukuyomi server]
    S -->|"/api/v1  rows"| T
    S <-->|"/realtime/v1  WebSocket"| T
    T --> P[(PostgreSQL<br/>project schema + policies)]
```

The demo has no server of its own. Its whole backend is two tables and their rules
(`scripts/demo-project.mjs`); the page ships the project's anon key, and the database decides what
that key and each logged-in user may do. `sdk/src/query.ts` builds the URLs described in the
README, `sdk/src/auth.ts` keeps the login fresh, and `sdk/src/live.ts` is the client half of the
live-update flow above (reconnect, cursor, repeated events).

## Limits: what stops one caller from taking the server

```mermaid
flowchart LR
    R[request] --> A{declared body<br/>over 1 MB?}
    A -->|yes| X1[413 too_large]
    A -->|no| B{credentials endpoint?<br/>10 per minute per address}
    B -->|over| X2[429 rate_limited]
    B --> C{per address<br/>20 per second, burst 100}
    C -->|over| X2
    C --> D{per API key<br/>50 per second, burst 200}
    D -->|over| X2
    D --> S[security filters, then the handler]
```

`common/limit/RateLimitFilter.java` runs before everything else, so a refused request costs no
database query and no password hash. Each limit is a token bucket (`TokenBucket.java`) kept in
memory. The caller's address is read from `X-Forwarded-For` counting from the right
(`ClientAddress.java`), because the left side of that header is written by the client.

Limits enforced elsewhere:

| Limit | Value | Where |
|---|---|---|
| Rows per table | 10,000 | trigger `tsk.enforce_row_quota` (`V5`, `V6`) |
| Tables per project, columns per table | 15, 30 | `MigrationPlanner` |
| Projects per account, end users per project | 3, 1,000 | `ProjectService`, `AuthService` |
| Page size, expansion depth, total rows per query | 100, 2, 25,000 | `QueryLimits` |
| Statement time | 3 seconds | `TenantTransaction` (`SET LOCAL statement_timeout`) |
| Live connections | 300 per server, 100 per project, 20 per address | `LiveSocketHandler` |
