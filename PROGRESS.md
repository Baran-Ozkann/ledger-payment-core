# Progress

**Current phase:** 0 — Skeleton
**Branch:** phase-0-skeleton
**Last updated:** 2026-09-04 00:20

## Done in this phase
- [x] Maven project, Java 21, Spring Boot 4.1.1, package layout — 13c4115
- [x] docker-compose.yml: PostgreSQL 16 and Kafka 4.3.1 in KRaft mode, both healthy — 402b6a1
- [x] V1 baseline migration, AbstractIntegrationTest on Testcontainers, smoke test — b8e5c16
- [x] ci/check-rules.sh, verified against a deliberate BigDecimal violation in domain — 949e4bb
- [x] .github/workflows/ci.yml: rule guard, then `mvn -B verify` — f8799b2
- [x] CI run green on GitHub Actions; actions pinned to checkout@v5 / setup-java@v5

## In progress
- Nothing; the phase deliverables are complete pending the CI run on GitHub

## Blocked / open questions
- `spring-boot-testcontainers` and `spring-boot-flyway` are not in the phase dependency list
  but are required by it: `@ServiceConnection` lives in the former, and Spring Boot 4 moved
  Flyway auto-configuration into the latter. Both are added; approval requested in the report.

## Next step
- Merge `phase-0-skeleton` into `main` once the GitHub Actions run is green
