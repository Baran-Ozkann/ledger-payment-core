# Progress

**Current phase:** 0 — Skeleton
**Branch:** phase-0-skeleton
**Last updated:** 2026-09-04 00:07

## Done in this phase
- [x] Maven project, Java 21, Spring Boot 4.1.1, package layout — 13c4115
- [x] docker-compose.yml: PostgreSQL 16 and Kafka 4.3.1 in KRaft mode, both healthy — 402b6a1
- [x] V1 baseline migration, AbstractIntegrationTest on Testcontainers, smoke test

## In progress
- CI rule guard and GitHub Actions workflow

## Blocked / open questions
- `spring-boot-testcontainers` and `spring-boot-flyway` are not in the phase dependency list
  but are required by it. Raised in the report for approval.

## Next step
- Add ci/check-rules.sh and demonstrate that it catches a deliberate violation
