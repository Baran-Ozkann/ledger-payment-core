# Progress

**Current phase:** 0 — Skeleton
**Branch:** phase-0-skeleton
**Last updated:** 2026-09-04 00:09

## Done in this phase
- [x] Maven project, Java 21, Spring Boot 4.1.1, package layout — 13c4115
- [x] docker-compose.yml: PostgreSQL 16 and Kafka 4.3.1 in KRaft mode, both healthy — 402b6a1
- [x] V1 baseline migration, AbstractIntegrationTest on Testcontainers, smoke test — b8e5c16
- [x] ci/check-rules.sh, verified against a deliberate BigDecimal violation in domain

## In progress
- GitHub Actions workflow

## Blocked / open questions
- `spring-boot-testcontainers` and `spring-boot-flyway` are not in the phase dependency list
  but are required by it. Raised in the report for approval.

## Next step
- Add .github/workflows/ci.yml and confirm the run is green
