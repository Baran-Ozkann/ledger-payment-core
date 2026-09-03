# Progress

**Current phase:** 0 — Skeleton
**Branch:** phase-0-skeleton
**Last updated:** 2026-09-03 23:55

## Done in this phase
- [x] Maven project, Java 21, Spring Boot 4.1.1, package layout — commit pending

## In progress
- Docker Compose stack, Flyway baseline, test harness, CI rule guard and workflow

## Blocked / open questions
- `spring-boot-testcontainers` is not in the phase dependency list but `@ServiceConnection`
  lives in it. Added, and raised in the report for approval.

## Next step
- Write docker-compose.yml with PostgreSQL 16 and Kafka in KRaft mode
