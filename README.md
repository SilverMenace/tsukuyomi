# Tsukuyomi

**Design your tables in a browser and get a secured REST API, user auth, row-level permissions and
live updates, without writing backend code.** A small, self-hostable backend-as-a-service.

**Live:** https://tsukuyomi-l9kg.onrender.com (free hosting: the first request after 15 idle minutes takes about a minute to wake the server)

> Status: under construction (M1 of 7 done: sign up, create a project, design tables, insert and
> list rows over REST with an API key). See `PLAN.md` for the build order.

## Run locally

Requirements: Java 21+, Node 22+, Docker.

```bash
scripts/dev-db.sh                                   # PostgreSQL in Docker on port 5433
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
cd dashboard && npm install && npm run dev          # in a second terminal
```

Open http://localhost:5173. The dashboard dev server forwards API calls to the backend on 8080.

Tests: `cd backend && ./mvnw verify`. They start a throwaway PostgreSQL in Docker. With Colima, first run
`export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock" TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`.

To run against a hosted database instead, put its `postgresql://` URL in `.env` and run
`eval "$(python3 scripts/dev-env.py)"` before starting the backend without the `dev` profile.

## Documents

- `PLAN.md`: architecture, data model, milestones
- `DECISIONS.md`: every significant choice and its trade-offs
- `ARCHITECTURE.md`: request flows

## Next (not in v1)

File storage, webhooks, billing, teams, visual polish, email verification and password reset,
OAuth logins, custom SQL rule expressions, WAL-based realtime, multi-instance realtime.

---

Built with Claude Code. The design decisions and the learning are mine.
