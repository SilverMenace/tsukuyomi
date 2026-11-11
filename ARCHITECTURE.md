# Architecture

How a request travels through the system. This file grows with each milestone; a section is only
written once the code for it exists, so everything here can be checked against the source.

For the intended end state, see the diagram in `PLAN.md` section 1.

## What exists today (M0)

```mermaid
sequenceDiagram
    participant C as Client
    participant R as Render (container)
    participant S as Spring Security filter chain
    participant A as Actuator health endpoint
    participant P as PostgreSQL (Neon)

    C->>R: GET /actuator/health
    R->>S: request enters the filter chain
    S->>A: path is on the allow list
    A->>P: SELECT 1 (through the connection pool)
    P-->>A: ok
    A-->>C: 200 {"status":"UP"}

    C->>R: GET /anything-else
    R->>S: request enters the filter chain
    S-->>C: 403 (deny by default, never reaches a controller)
```

At startup, Flyway connects as the owner role, creates schema `tsk_platform` if needed, and applies
any new files from `backend/src/main/resources/db/migration`.

## Coming next

- M1: control-plane request (dashboard → create table → DDL in one transaction).
- M2/M3: data API request (API key → identity → query compiler → tenant transaction → RLS).
- M4: live-update subscription (trigger → outbox → NOTIFY → listener → WebSocket).
