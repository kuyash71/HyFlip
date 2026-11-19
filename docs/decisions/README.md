# Architecture Decision Records

Each record captures one decision: the context, what was decided and what follows from it. Records are not edited after they are accepted; a new record supersedes an old one.

| # | Decision | Status |
| --- | --- | --- |
| [0001](0001-local-first-read-only.md) | Local-first, read-only app with a localhost server | Accepted |
| [0002](0002-java-spring-boot-server.md) | Java 17 and Spring Boot with Maven for the server | Accepted |
| [0003](0003-tauri-shell-system-java.md) | Tauri shell with React + TypeScript, server run on the user's Java | Accepted |
| [0004](0004-sqlite-jdbc-flyway.md) | SQLite through Spring JDBC and Flyway, no ORM | Accepted |
| [0005](0005-personal-api-keys.md) | Personal Hypixel API keys for now | Accepted |
| [0006](0006-collect-while-open.md) | Collect data only while the app is open, backfill gaps on start | Accepted |
| [0007](0007-profit-per-session.md) | Rank flips by profit per session; metric definitions | Accepted |

## Template

```markdown
# NNNN. Title

- Status: Proposed | Accepted | Superseded by NNNN
- Date: YYYY-MM-DD

## Context
## Decision
## Consequences
```
