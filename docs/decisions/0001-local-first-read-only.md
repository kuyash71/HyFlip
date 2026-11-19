# 0001. Local-first, read-only app with a localhost server

- Status: Accepted
- Date: 2026-11-17

## Context

Hypixel bans tools that automate in-game actions, and its API policy forbids proxying the Public API to third-party developers. Players want personalised flip rankings without risking their account or sharing their data.

## Decision

- HyFlip reads and shows; it never sends clicks, commands, chat messages or Bazaar/AH orders.
- It is a standalone desktop app, not a Minecraft mod.
- Each user runs their own instance. A local server is the only component that calls outside sources; the UI talks to it over REST on `127.0.0.1`.
- The API is protected by a per-launch token so web pages in the user's browser cannot read it.

## Consequences

- No ban risk from the app itself; every in-game step is manual and the guides say so.
- No shared backend to run or pay for, but each instance builds its own history (cold start, see 0006).
- Data the API does not expose (live order states) stays out of reach until an optional read-only companion mod.
