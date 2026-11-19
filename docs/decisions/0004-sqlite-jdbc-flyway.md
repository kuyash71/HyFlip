# 0004. SQLite through Spring JDBC and Flyway, no ORM

- Status: Accepted
- Date: 2026-11-17

## Context

Data is mostly append-only time series (snapshots every minute), queried by item and time range, on the user's machine. It must be a single file that can be exported and imported.

## Decision

- **SQLite** (`org.xerial:sqlite-jdbc`), one file at `<data-dir>/hyflip.db`, WAL mode, foreign keys on.
- **Spring JDBC** (`JdbcClient`) for access, hand-written SQL.
- **Flyway** for schema migrations (`db/migration/V<n>__*.sql`).
- Hibernate/JPA is not used: SQLite needs a community dialect, and snapshot inserts and time-range aggregates are simpler and faster as plain SQL.

## Consequences

- No server to install; the database is one file, easy to back up, export (`VACUUM INTO`) and publish.
- Bulk inserts and aggregates are explicit and easy to tune.
- More boilerplate than JPA for simple CRUD tables (config-like data), which is acceptable at this size.
- One writer at a time: collectors write in short transactions; the pool is kept small.
