# 0002. Java 17 and Spring Boot with Maven for the server

- Status: Accepted
- Date: 2026-11-17

## Context

The server polls several REST APIs on schedules, stores snapshots, runs calculations and serves a REST API. The maintainer has deep experience with Java and Spring and basic Kotlin.

## Decision

- Java 17 (the current baseline of Spring Boot 4), Spring Boot 4, Maven with the wrapper committed.
- Spring features used: `@Scheduled` for collectors, Spring MVC for the API, `RestClient` for outbound HTTP, `@ConfigurationProperties` records for the YAML config.
- Package by feature (`source`, `collector`, `mapping`, `storage`, `engine`, `flipper`, `alert`, `profile`, `api`).

## Consequences

- Fast development in a familiar stack with first-class scheduling, config binding and testing.
- Costs: about 1.5–2 s start-up and roughly 150–250 MB memory, acceptable for a desktop helper.
- A Java runtime is required at run time (see 0003). Moving to Java 21 later is a one-line change if needed.
