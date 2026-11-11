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
