# 0005. Personal Hypixel API keys for now

- Status: Accepted
- Date: 2026-11-17

## Context

Profile features (HOTM, collections, purse, forge data) need a Hypixel API key. Personal development keys are free but expire every 3 days. Application keys are permanent but require an approved application and suit a project-owned server.

## Decision

- For now each user creates and renews their own personal key and enters it in the app.
- The app stores when the key was entered and warns before it expires and when Hypixel rejects it.
- Everything that does not need a key (Bazaar, auctions, resources, recipes) keeps working without one.

## Consequences

- No approval process or shared infrastructure to start.
- Users with profile linking must renew the key every 3 days; manual config values remain as a fallback.
- Later: move to an application key with a project-owned server, designed so it does not proxy the Public API to third parties.
