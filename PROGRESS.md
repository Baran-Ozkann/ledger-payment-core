# Progress

**Current phase:** 0 — Skeleton
**Branch:** phase-0-skeleton
**Last updated:** 2026-09-04 00:05

## Done in this phase
- [x] Maven project, Java 21, Spring Boot 4.1.1, package layout — 13c4115
- [x] docker-compose.yml: PostgreSQL 16 and Kafka 4.3.1 in KRaft mode, both with healthchecks

## In progress
- Flyway baseline, test harness, CI rule guard and workflow

## Blocked / open questions
- `spring-boot-testcontainers` and `spring-boot-flyway` are not in the phase dependency list
  but are required by it. Raised in the report for approval.

## Next step
- Add the Flyway baseline migration and the Testcontainers integration test harness
