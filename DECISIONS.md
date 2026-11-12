# Decisions

Every significant choice, what else was on the table, why this one won, and what it costs.
Newest milestones are at the bottom. Each entry is written so you can answer
"why X and not Y?" out loud.

---

## M0 — foundations

### D1. Spring Web MVC with virtual threads, not WebFlux

- **Question:** how does the server handle many requests at once?
- **Options:**
  1. *Web MVC, one platform thread per request.* Simple code, but each waiting request holds an
     operating-system thread (about 1 MB of stack each), so a few hundred slow requests exhaust the pool.
  2. *WebFlux (reactive).* Scales well, but every database call has to be non-blocking, the code
     becomes chains of `Mono`/`Flux`, and stack traces are hard to read.
  3. *Web MVC on virtual threads (Java 21).* Same simple blocking code as option 1, but the thread
     a request runs on is a cheap JVM object. When it waits on the database, the real thread underneath
     is released to do other work.
- **Choice:** option 3 (`spring.threads.virtual.enabled=true`).
- **Trade-off:** concurrency is now limited by the database pool (5 connections), not by threads.
  That is the correct bottleneck for this app, but it means a slow query hurts everyone, which is
  why every tenant query will run with a statement timeout.

### D2. Plain JDBC (`JdbcTemplate`), not JPA/Hibernate

- **Question:** how does Java talk to the database?
- **Options:** JPA/Hibernate maps Java classes to tables; jOOQ generates a typed SQL API from a
  schema; `JdbcTemplate` runs SQL strings with bind parameters.
- **Choice:** `JdbcTemplate`, with our own SQL builder for tenant queries.
- **Why:** JPA and jOOQ both need the tables to be known when the code is compiled. Here users
  create tables while the app is running, so there is no class to map. Writing the SQL builder
  ourselves is also the point of the project.
- **Trade-off:** no automatic mapping, more code for our own platform tables. Acceptable: there are
  under ten of them.

### D3. One schema and one set of roles per project, enforced by Postgres

- **Question:** how are projects kept apart on one database?
- **Options:**
  1. *Shared tables with a `project_id` column*, filtered by the API. Cheapest, but isolation then
     depends on every query remembering the filter. One missing `WHERE` leaks another tenant's data.
     It also cannot work here because each project has different tables.
  2. *A database per project.* Strongest isolation, but each needs its own connection pool, and
     free hosting gives us one database.
  3. *A schema per project plus Postgres roles.* Each project's tables live in schema `p_<id>`.
     Three roles per project have access to that schema only.
- **Choice:** option 3. The data API logs in as a role with no privileges (`NOINHERIT`, no grants)
  and must `SET LOCAL ROLE` into one project's role for each transaction.
- **Why:** the database refuses cross-project access even if our Java code is wrong.
- **Trade-off:** roles are global to the Postgres cluster, so thousands of projects would mean
  thousands of roles. Fine at our quota (3 projects per account); a real product at scale would
  revisit this.
- **Evidence:** `scripts/spike/IsolationSpike.java` checks every assumption against the hosted
  database. Result on Neon (PostgreSQL 18): all pass.

### D4. `SET LOCAL` inside a transaction, never plain `SET`

- **Question:** connections are pooled and reused. How do we stop one request's identity from
  being seen by the next request on the same connection?
- **Choice:** role, user id, search path and timeout are all set with `SET LOCAL` /
  `set_config(..., true)`, which Postgres undoes automatically at commit or rollback.
- **Why not plain `SET` plus a reset afterwards:** a reset is code that can be skipped by an
  exception. With `SET LOCAL` there is nothing to forget.
- **Evidence:** spike checks 5 and 5b (role and setting are gone after commit), and check 7
  (works through a transaction-mode pooler).

### D5. Security is deny-by-default

- **Choice:** the Spring Security configuration ends with `anyRequest().denyAll()`. Each feature
  opens its own routes explicitly.
- **Why:** if someone adds a controller and forgets to configure it, the route is unreachable
  instead of public. Failing closed is the safe direction.

### D6. Hosting: Render (app) + Neon (Postgres), both free, no card

- **Options:** Oracle Cloud always-free VM (always on, full control, needs a card); Fly.io and
  Cloud Run (need a card); Render + Neon (no card).
- **Choice:** Render + Neon, both in Singapore to keep the app close to its database.
- **Trade-offs:**
  - Render sleeps the app after 15 idle minutes; the first request then waits about a minute.
  - 512 MB RAM and a fraction of a CPU, so the JVM is tuned small (`Dockerfile`).
  - Neon suspends an idle database after 5 minutes. A `LISTEN` connection is dropped when that
    happens, so the realtime listener must reconnect and catch up (designed in M4).
- **A finding worth remembering:** Neon's pooler keeps server sessions open per role name. When
  the spike dropped and recreated a role with the same name, the pooler reused a session that
  belonged to the *old* role, which had none of the new grants. Lesson: the long-lived login role
  is created once and never dropped.

### D7. Standard `SPRING_DATASOURCE_*` environment variables

- **Choice:** production configuration uses Spring Boot's own variable names rather than a custom
  `DATABASE_URL` parser inside the app.
- **Why:** zero code, and tests can swap in a throwaway database with one annotation
  (`@ServiceConnection`). `scripts/dev-env.py` converts the host's `postgresql://` URL for local runs.

### D8. One deployable

- **Choice:** the dashboard will be built into the Spring Boot jar and served by it.
- **Why:** one free service, one URL, and no cross-origin setup for the dashboard.
- **Trade-off:** a dashboard change redeploys the backend. The demo app is deliberately separate,
  because it must prove it runs with no backend of its own.

---

## M1 — first end-to-end slice

### D9. Dashboard sessions: opaque token in a cookie, stored server-side

- **Question:** how does the dashboard stay logged in?
- **Options:**
  1. *JWT in `localStorage`.* Stateless, but any injected script can read it, and it cannot be
     revoked before it expires.
  2. *JWT in a cookie.* Safe from scripts, still not revocable.
  3. *Random token in a cookie, looked up in a `session` table.* One extra query per request, but
     logout really ends the session.
- **Choice:** option 3. The cookie is `HttpOnly` (scripts cannot read it), `Secure` (HTTPS only) and
  `SameSite=Strict`. The table stores only the SHA-256 of the token.
- **Trade-off:** one database lookup per dashboard request. The dashboard is low traffic, so that is
  cheap. (End users of projects get JWTs in M3, where statelessness matters more.)

### D10. No CSRF tokens; `SameSite=Strict` instead

- **The attack:** another website makes your browser send a request to our dashboard, and the
  browser attaches your cookie automatically.
- **Options:** Spring Security's CSRF tokens (a second secret the page must echo back), or
  `SameSite=Strict`, which tells the browser never to attach the cookie to a request that another
  site started.
- **Choice:** `SameSite=Strict`, with Spring's CSRF tokens switched off.
- **Why:** it removes the attack at the browser level with no token plumbing in the single-page app.
  The dashboard and API share one origin (D8), so Strict costs us nothing. The data API (`/api`) does
  not use cookies at all, so CSRF does not apply to it.
- **Trade-off:** relies on browser support for SameSite (all current browsers). A bank would use both.

### D11. API keys: random, shown once, stored as SHA-256

- **Choice:** a key is 24 random bytes with a readable prefix (`tsk_service_…`). We store its SHA-256.
- **Why SHA-256 and not BCrypt here:** BCrypt is deliberately slow to make guessing *short, human*
  passwords expensive. A key with 192 random bits cannot be guessed, so a fast hash is safe and keeps
  every API request cheap. Passwords still use BCrypt.
- **Trade-off:** a lost key cannot be shown again, only replaced.

### D12. Constant-time login for unknown emails; signup still reveals existing emails

- **Done:** when the email is unknown, login still runs one BCrypt comparison against a dummy hash,
  so "no such account" and "wrong password" take the same time and return the same message.
- **Accepted risk:** signup answers "email already exists". Hiding that needs email verification
  ("we sent you a link" in both cases), and v1 has no email provider. Rate limiting (M7) limits how
  fast emails can be probed. Listed under "Next".

### D13. The dashboard sends operations, the server writes the SQL

- **Options:** let the dashboard send DDL; or send structured operations
  (`{"op": "add_column", …}`) and generate DDL on the server.
- **Choice:** operations. `MigrationPlanner` validates every name against `^[a-z][a-z0-9_]{0,27}$`,
  takes type names from a fixed enum, and builds the SQL.
- **Why:** identifiers cannot be bind parameters, so the only safe approach is to accept a tiny
  alphabet that has no meaning in SQL. With operations there is no SQL from the client to sanitise.
- **Trade-off:** each kind of change has to be implemented as an operation.

### D14. DDL, catalog and history in one transaction

- **Choice:** a migration runs its DDL, replaces the catalog rows and inserts the history row in a
  single transaction, after locking the project row.
- **Why:** PostgreSQL can roll back DDL (MySQL and Oracle cannot). If step 3 of 5 fails, nothing
  happened. The lock stops two migrations interleaving. A dry run does the same work and rolls back,
  so the SQL preview is proven valid rather than guessed.
- **Catalog storage:** rewritten whole after each migration (it is a handful of rows), so it cannot
  drift from what the planner computed.

### D15. Postgres builds the JSON; values are sent as text and cast

- **Choice:** queries return one string produced by `json_agg` / `row_to_json`. Inserts bind every
  value as text and wrap it in `cast(? as <column type>)`.
- **Why:** user tables have unknown shapes, so there is no Java class to map rows into. Letting
  Postgres serialise gives correct JSON for every type (timestamps, numerics, jsonb) for free, and
  will make nested results simple in M2. Casting lets Postgres validate values; a bad value becomes
  SQLSTATE class 22, which we map to HTTP 400.
- **Trade-off:** error messages for bad values are Postgres's wording.

### D16. Row lock for quota checks

- **Problem:** "count projects, then insert" lets two simultaneous requests both pass the count.
- **Choice:** `SELECT … FOR UPDATE` on the account row first, so creations for one account queue up.
- **Evidence:** `EndToEndSliceTest.projectQuotaHoldsUnderConcurrentRequests` fires ten at once and
  ends with exactly three.

### D17. Main only receives tested commits

- **Choice:** work happens on a branch; CI runs on every branch; `main` is fast-forwarded when CI is
  green. Render deploys `main` automatically.
- **Why:** a platform migration (Flyway) that reaches the live database cannot be edited afterwards,
  so it should have passed tests first.

---

## M2 — query compiler

### D18. A compiler in three stages, with a trust boundary in the middle

- **Question:** how do you turn `?select=…&priority=gte.3&order=…` into SQL without ever letting
  request text become SQL?
- **Options:**
  1. *Build SQL strings directly in the controller*, escaping as you go. Every new feature is a new
     chance to forget an escape.
  2. *Use a query-builder library* (jOOQ, Criteria API). Safe for values, but table and column names
     are still strings we would pass in, and we would not learn how it works.
  3. *A small compiler:* parse → validate → build.
- **Choice:** option 3.
  - `QueryParser` only understands shape. Its output (`RawQuery`) is still untrusted text.
  - `QueryValidator` is the trust boundary. It looks every name up in the catalog and stores the
    catalog's object in the AST, not the request's string. Unknown name → 400.
  - `SqlBuilder` turns the AST into SQL.
- **Why this is safe by construction:** `SqlBuilder` has no public method that takes a `String`, and
  the only AST type that can hold request text is `Param`, which is only ever emitted as `?`.
  Identifiers pass through `Identifiers.quote`, which re-checks the name pattern, so even a bug in
  the validator cannot push unsafe text into SQL.
- **Trade-off:** more code than string building, and each new feature touches three places.

### D19. Proving it: four kinds of test, each catching what the others cannot

| Test | What it proves | What it cannot prove |
|---|---|---|
| `SqlBuilderContractTest` (reflection) | the structural rules above still hold after future edits | that the SQL is correct |
| `InjectionTest` corpus | known payloads, in every position, are rejected or bound | unknown payloads |
| `InjectionTest` fuzzer (40,000 seeded random requests) | no combination of SQL fragments yields SQL outside a strict alphabet | behaviour in a real database |
| `QueryApiTest` over HTTP + real Postgres | ~360 attacks change nothing in the database and never cause a 5xx | exhaustiveness |

- The unit tests judge the SQL with an **independent oracle** (`assertSafe`): only known identifiers
  in quotes, only fixed keywords outside them, placeholders equal to parameters. It does not reuse
  the builder's logic, so it cannot share the builder's blind spots.
- **Why a hand-written fuzzer and not jqwik** (as first planned): jqwik's engine targets the JUnit 5
  platform and the project is on JUnit 6. A seeded `java.util.Random` loop is 30 lines, has no
  dependency, and a failure reproduces exactly because the seed is fixed.
- **It found real bugs:** a zero byte in a parameter name returned 500 (now 400), and rows inserted in
  one batch came back in random order (see D22).

### D20. Related records as correlated sub-selects that return JSON

- **Options:** run one query per parent row (the "N+1" problem); join and regroup rows in Java; or
  let Postgres nest the related rows as JSON inside the parent row.
- **Choice:** a sub-select per expansion using `row_to_json` (one object) or `json_agg` (a list).
- **Why:** one round trip for any depth, no regrouping code, and row-level security applies to the
  related table automatically because the sub-select runs as the same role.
- **Limits:** depth 2, 100 related rows per parent, so one URL cannot ask for an unbounded tree.
  Expanding a table into itself is not supported yet.
- **An index on every foreign-key column** is created by the planner: Postgres indexes the
  referenced side but not the referencing side, and to-many expansion filters on the latter.

### D21. URL syntax modelled on PostgREST

- **Choice:** `column=operator.value`, `select=a,b,rel(c)`, `order=col.desc`.
- **Why:** it is a proven design with public documentation to compare against, it maps cleanly to
  query strings without a request body, and `*` as the `like` wildcard avoids `%` escaping in URLs.
- **Trade-off:** no `OR` between filters in v1; a value for `in` cannot contain a comma.

### D22. `uuidv7()` ids, and a total order for pagination

- **Problem found by a test:** `created_at` defaults to `now()`, which is the *transaction's* start
  time, so rows inserted in one batch tie, and random UUIDs broke the tie randomly.
- **Choice:** ids default to `uuidv7()` (PostgreSQL 18), whose leading bits are a timestamp. Every
  query ends its `ORDER BY` with `id`.
- **Why:** offset pagination is only correct if the order is total; otherwise a row can appear on
  two pages or none. Time-ordered ids also keep the primary-key index compact, because new entries
  always go at the end instead of in random places.
- **Trade-off:** requires PostgreSQL 18+; ids reveal creation time (so does `created_at`).

### D23. Updates and deletes must have a filter

- **Choice:** `PATCH` and `DELETE` without any filter are rejected with 400.
- **Why:** "change every row" is almost always a mistake, and it is the most destructive one the API
  can make. Being explicit costs the caller one parameter.

### D24. Limits on every request

- **Choice:** `QueryLimits` caps page size (100), offset, number of filters (20), `in` list (100),
  value length, expansion depth and batch size; `TenantTransaction` adds a 3-second statement timeout.
- **Why:** on shared infrastructure, one tenant's expensive request is every tenant's slow request.
- **A total row budget, not only per-level caps:** each list expansion can multiply its parent's
  rows by 100, so three levels could be 100 x 100 x 100 = one million rows from one URL. The
  validator adds up the worst case across all levels and refuses anything over 25,000 before it
  runs. (This gap was caught by an automated security review of the first version.)

---

## M3 — end-user auth and permission rules

### D25. Rules are Postgres row-level security policies, not checks in Java

- **Question:** where is "users can only read rows they own" enforced?
- **Options:**
  1. *In the API:* add `WHERE owner_id = ?` to every generated query. Simple, but one forgotten
     code path (an expansion, a new endpoint, the realtime feed) leaks data.
  2. *In the database:* each rule becomes a `CREATE POLICY`. Postgres applies it to every row of
     every statement, including sub-selects, whatever SQL arrives.
- **Choice:** option 2. The API layer does not contain a single ownership check.
- **How a rule maps to a policy:**

  | Rule | Policy is for roles | Condition |
  |---|---|---|
  | nobody | (no policy) | row-level security denies by default |
  | anyone | anon, user | `true` |
  | authenticated | user | `true` |
  | owner | user | `<column> = tsk.uid()` |

  `USING` filters which existing rows an operation can see; `WITH CHECK` validates the new version
  of a written row. That second part is what stops a user inserting a row "owned" by someone else,
  or handing their row to another user.
- **Rules are structured data**, never SQL text, so there is no injection path through a rule.
- **Trade-off:** only four rule shapes in v1 (no custom expressions). A write returns the written
  row, so a table you can insert into but not read from is not supported yet.

### D26. Who the caller is: two headers, decided in one place

- `apikey` says which project and whether the caller is the public app (anon) or trusted server
  code (service). An optional `Authorization: Bearer <token>` says which end user.
- `ApiKeyFilter` turns those into a `TenantIdentity`; `TenantTransaction` turns that into
  `SET LOCAL ROLE` plus `tsk.user_id`. Nothing in the path, query or body can influence it.
- **An invalid token is a 401, not "treat as anonymous".** Silently downgrading would make a client
  with an expired token see an empty list and believe there is no data.

### D27. What the database-level guarantee covers, and what it does not

This is the answer to "can a bug in your API leak data?", and the honest version has two halves.

- **Covered: wrong queries.** A missing filter, the wrong table, an expansion that forgot a check,
  a new endpoint written carelessly. `AuthAndRulesTest.aBrokenApiLayerStillCannotLeak…` runs
  filter-less `SELECT`, `UPDATE` and `DELETE` as a user and other users' rows are untouched.
  Password hashes and all platform tables are unreadable from every tenant role, including service.
- **Not covered: arbitrary SQL execution.** The data plane logs in as `tsk_api`, which may switch
  into any project role. A second `SET ROLE` is just another statement, so if an attacker could
  run SQL of their choosing they could switch to a service role. Postgres has no way for a session
  to give up that ability. `knownLimitArbitrarySqlCouldSwitchRole` states this as a test.
- **So two independent defences each cover one half:** the query compiler guarantees no request
  text becomes SQL (D18, D19); row-level security guarantees that the SQL we do generate cannot
  cross users or projects.
- **This is the same model as PostgREST and Supabase** (their `authenticator` role can switch to
  any role).
- **How to close the gap, and why not now:** give each project its own login role and connection
  pool, so the role a connection can become is limited to one project. That costs a pool per
  project, which a free database with a small connection limit cannot afford.

### D28. JWT for end users, written by hand; opaque tokens for refresh

- **Why JWT here but a session table for the dashboard (D9):** end-user requests are the hot path.
  A signed token is verified with one HMAC and no database lookup.
- **Why hand-written:** it is about 100 lines, and the three classic mistakes are each visible as a
  line of code and a test: the algorithm is fixed by us and never read from the token (`alg: none`
  attack), the signature comparison is constant-time, and the payload is only parsed after the
  signature is verified.
- **Per-project secret, and the project id inside the token:** a token from one project fails in
  another even if secrets were ever shared.
- **Short access token (1 hour) + single-use refresh token (30 days, stored hashed):** a stolen
  access token expires quickly; a refresh token can be revoked because it is a row. Using one
  deletes it in the same statement that reads it, so two racing requests cannot both succeed.
- **Trade-off:** an access token stays valid until it expires, even after logout or deletion.

### D29. Profiles in the project, credentials in the platform

- Each project has a built-in `users` table (id, email) inside its own schema, so the owner's tables
  can reference users and rules can say "owner". Password hashes are in `tsk_platform.end_user`.
- **Why split:** the project's tables are reachable through the data API by design; credentials must
  not be reachable by any tenant role under any rule the owner could configure.
- Tenant roles get `SELECT` and `UPDATE` on `users`, never `INSERT` or `DELETE`: rows come from
  signup only.

### D30. CORS open for the public API, closed for the dashboard API

- `/api` and `/auth` answer any origin: apps built on a project run on their own domains.
  That is safe because these endpoints never read a cookie, so a foreign page cannot borrow a
  visitor's login; it can only use keys and tokens it already has.
- `/platform` (cookie session) sends no CORS headers, so only the dashboard's own origin can call it.

## M4 — live updates

### D31. Triggers and LISTEN/NOTIFY, not the write-ahead log

The server has to find out that a row changed. Two ways:

- **Read the write-ahead log (logical replication).** Postgres streams every committed change to
  a consumer. This is what Supabase Realtime does. It adds no work to the writing transaction and
  scales to high write rates. It needs a replication slot, a decoder for the stream, and care:
  a slot whose consumer is down makes the database keep log files until the disk is full.
- **A trigger on each table (chosen).** `tsk.record_change()` runs after every insert, update and
  delete, writes one small row to `tsk_platform.change_event` and calls `pg_notify`. One
  connection of ours runs `LISTEN` and receives the notification when the transaction commits.
- **Why:** it is plain SQL that works on any Postgres, including a free hosted one, and it fits in
  the time available. The whole mechanism is one SQL function and one Java class.
- **Cost:** every write does one extra insert and one notify, inside the writer's transaction.
  A batch insert of 100 rows makes 100 log rows. Fine at this scale; it is the reason large
  systems use the log instead. Listed in the README under "Next".

### D32. A change says which row, never what is in it

- The notification and the log row hold: table, operation, row id, and two transaction numbers.
  They never hold column values.
- For each change, the hub reads the row **once per distinct identity** subscribed to that table,
  through the same `RowService` and `TenantTransaction` as a normal `GET`. Row-level security
  answers "may this identity see it?". Not visible means no message. There is no second copy of
  the rules in Java that could drift from the policies.
- **Alternative:** put the new row into the notification and filter in Java. Faster (no read per
  identity) but the permission check would be re-implemented outside the database, which is
  exactly what D25 refuses. Also, `NOTIFY` payloads are limited to about 8 KB.
- **Cost:** one small transaction per identity per change. 50 users watching one table means 50
  reads for each change to it. Identities are grouped, so 50 anonymous visitors cost one read.
- **Deletes are the exception.** A deleted row cannot be read, so row-level security cannot
  answer for it. The first version sent deletes to every subscriber of the table with only the
  row id; a security review pointed out that this tells people about rows they could never see
  (Supabase accepts this trade-off). Now the trigger keeps the deleted row in the log
  (`old_row`, never sent to clients), and `LiveHub.couldSeeDeletedRow` applies the table's read
  rule to it: service always; `anyone` everyone; `authenticated` logged-in users; `owner` only
  the user whose id was in the owner column; `nobody` no one else.
- **Cost of that:** this is the one place where a rule is evaluated in Java, so it must be kept
  in step with the policies `MigrationPlanner` generates. It uses the rule as it is now, not as
  it was when the row was deleted. When unsure it does not send. Only the id is ever sent.
- **Updates that hide a row** (the owner column changes): the old owner gets no message, because
  the row is no longer visible to them. Their copy goes stale until they reload.

### D33. The cursor is a transaction number, not an event id

A client that loses its connection sends back a cursor and gets what it missed from the log.
The obvious cursor, "last event id I saw", is wrong:

- Event ids come from a sequence **when the row is written**. Transactions commit in a different
  order. A writes (event 5), B writes (event 7) and commits, the client receives 7 and
  disconnects, then A commits. "Everything after 7" never returns 5.
- **What we store instead:** with each event, the trigger records `oldest_running`: the lowest
  transaction id still in progress at that moment (`pg_snapshot_xmin`). Every transaction below
  that number had already finished, so its notification was sent, and delivered, earlier.
- **The cursor** sent with each event is the largest `oldest_running` the subscription has seen.
  Replay is "events from transactions with id >= cursor". In the example, A was still running
  when B wrote, so B's cursor is at most A's id, and A is included.
- **Price: at-least-once.** Replay can repeat an event the client already has (always the last
  one, at least). Each event carries an `eventId`; clients skip ids they have seen.
- `LiveUpdatesTest.aChangeThatCommitsLateIsNotSkippedOnReplay` holds a transaction open to
  produce exactly this order.
- **Alternative considered:** assign a strictly increasing number at commit time. Postgres has no
  cheap way to do that without making all writers queue on one lock.

### D34. When the server cannot know what was missed, it says "reload"

Three cases end with a `resync` message (or `"resync": true` in the answer to `subscribe`):

1. The cursor is older than the log. Events are kept for 10 minutes; `change_floor` records how
   far the log has been trimmed.
2. More than 200 events were missed. Replaying them one read at a time would stall everyone else.
3. The server's own `LISTEN` connection dropped (the free database restarts or sleeps).
   Notifications are not stored by Postgres for absent listeners.

- **Why not replay server-side in case 3:** it is possible with the same cursor idea, but it is a
  second replay mechanism for a rare event. Telling clients to reload is always correct.
- **Honest consequence:** a reconnect after more than 10 idle minutes always reloads, even if
  nothing changed, because the server cannot prove that.

### D35. One thread dispatches everything

- `LiveHub` runs changes, subscribes, unsubscribes and disconnects on a single thread; other
  threads only add tasks to its queue. The subscription maps need no locks, replay cannot
  interleave with live events, and each client receives changes in commit order.
- **Cost:** throughput is one database read at a time. If the queue reaches 10,000 tasks the
  backlog is dropped and every client is told to reload (D34) rather than falling further behind.
- **Subscriptions live in memory**, so live updates work on one server instance only. A second
  instance would need each instance to listen and fan out to its own clients; that part would
  already work, but connection limits would then be per instance.

### D36. The listener exists only while someone is subscribed

- The `LISTEN` connection opens with the first subscription and closes with the last.
- **Why:** the free database goes to sleep after five idle minutes, which is what keeps it free.
  A permanent connection that we keep checking would hold it awake around the clock. For the
  same reason the log is trimmed only when something was written since the last trim.
- **Order matters on start:** `LISTEN` first, then choose the starting cursor. The other way
  round, a change committing in between would be neither replayed nor heard.

### D37. WebSocket authentication is the first message

- Browsers cannot set headers on a WebSocket, and a key in the URL would end up in server logs.
  So the handshake is open and the first message must be `auth` with the API key and optional
  token; nothing else is accepted before it, and silent connections are closed after 10 seconds.
- The key and token go through the same `IdentityResolver` as HTTP requests, so there is one
  definition of "who is this".
- A WebSocket is checked once, when it connects, unlike HTTP where every request shows its key.
  So when a key is replaced or a project deleted, `ProjectService` publishes
  `ProjectAccessChanged` and every live connection of that project is closed (found by a
  security review; without it a leaked key would keep working on open connections).
- A connection's identity never changes. When a user's token expires the server closes the
  connection with code 4401; the client reconnects with a fresh token and its cursor.
- **Measured on the live host:** the error message arrives at once and the server stops sending
  at once (checked by replacing a key and then changing a row), but the client sees the
  connection end about 20 seconds later with code 1006 instead of 4401. Locally it is immediate
  with 4401. The close frame does not make it through the host's proxy. So clients must act on
  the error message, not wait for the close. Cause not investigated yet.
- **Known gap:** connections that have not authenticated yet count towards the server-wide
  limit for up to 10 seconds, and there is no per-address limit, so one machine can use up the
  slots. Per-address limits come with rate limiting in M7.
- Limits: 300 connections per server, 100 per project, 20 tables per connection, 4 KB per
  message. A client that cannot take its messages within 5 seconds is disconnected.
