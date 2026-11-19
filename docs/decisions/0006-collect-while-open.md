# 0006. Collect data only while the app is open, backfill gaps on start

- Status: Accepted
- Date: 2026-11-17

## Context

Hypixel returns only current data, so history must be collected continuously. A background service running all the time is heavier to install and support. `auctions_ended` only covers the last minute, so anything missed is lost.

## Decision

- The server runs only while the app is open and collects during that time.
- On start it detects the gap since the last successful run. If a Coflnet token is configured, it backfills Bazaar and auction history from Coflnet; otherwise the gap is recorded and shown.
- Published databases and import reduce the cold start for new users.

## Consequences

- Simple install: no system service.
- History has holes when the app is closed and no token is set; alerts that need history say when it is limited.
- Later: an always-on collection server can replace this.
