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
