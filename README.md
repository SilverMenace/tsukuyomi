# Tsukuyomi

**Design your tables in a browser and get a secured REST API, user auth, row-level permissions and
live updates, without writing backend code.** A small, self-hostable backend-as-a-service.

**Live:** https://tsukuyomi-l9kg.onrender.com (free hosting: the first request after 15 idle minutes takes about a minute to wake the server)

> Status: under construction (milestone M0 of 7 done). See `PLAN.md` for the build order.

## Run locally

Requirements: Java 21+, Docker (for tests).

```bash
cp .env.example .env            # put a postgresql:// URL in it
eval "$(python3 scripts/dev-env.py)"
cd backend && ./mvnw spring-boot:run
curl localhost:8080/actuator/health
```

Tests: `cd backend && ./mvnw verify` (starts a throwaway PostgreSQL in Docker).

## Documents

- `PLAN.md`: architecture, data model, milestones
- `DECISIONS.md`: every significant choice and its trade-offs
- `ARCHITECTURE.md`: request flows

## Next (not in v1)

File storage, webhooks, billing, teams, visual polish, email verification and password reset,
OAuth logins, custom SQL rule expressions, WAL-based realtime, multi-instance realtime.

---

Built with Claude Code. The design decisions and the learning are mine.
