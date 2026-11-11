# Architecture

How a request travels through the system. This file grows with each milestone; a section is only
written once the code for it exists, so everything here can be checked against the source.

For the intended end state, see the diagram in `PLAN.md` section 1.

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
    participant RS as RowService
    participant TT as TenantTransaction
    participant P as PostgreSQL (tsk_api)

    C->>K: GET /api/v1/notes + apikey header
    K->>P: key hash -> project + role (owner pool)
    K->>DC: request now carries a TenantIdentity
    DC->>RS: list(identity, "notes", limit, offset)
    RS->>RS: look "notes" up in the catalog (404 if absent), build SQL from catalog names
    RS->>TT: execute(identity, work)
    TT->>P: BEGIN
    TT->>P: SET LOCAL ROLE p_x_service, search_path, statement_timeout, tsk.user_id
    TT->>P: SELECT json_agg(...) with bind parameters
    Note over P: table grants + row-level security decide what is visible
    TT->>P: COMMIT (role and settings vanish)
    TT-->>C: JSON text straight from Postgres
```

The dashboard's data browser takes the same path from `RowService` onwards, as the service role.

## Coming next

- M2: the query compiler replaces the simple SQL building in `RowService`.
- M3: end-user tokens add a `USER` identity; rules become row-level security policies.
- M4: live-update subscription (trigger → outbox → NOTIFY → listener → WebSocket).
