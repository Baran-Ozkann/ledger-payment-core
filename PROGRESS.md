# Progress

**Current phase:** 0 — Skeleton
**Branch:** phase-0-skeleton
**Last updated:** 2026-09-04 11:35

## Done in this phase
- [x] Maven project, Java 21, Spring Boot 4.1.1, package layout — 13c4115
- [x] docker-compose.yml: PostgreSQL 16 and Kafka 4.3.1 in KRaft mode, both healthy — 402b6a1
- [x] V1 baseline migration, AbstractIntegrationTest on Testcontainers, smoke test — b8e5c16
- [x] ci/check-rules.sh, verified against a deliberate BigDecimal violation in domain — 949e4bb
- [x] .github/workflows/ci.yml: rule guard, then `mvn -B verify` — f8799b2
- [x] CI run green on GitHub Actions; actions pinned to checkout@v5 / setup-java@v5 — 1f41f5c
- [x] Remediation: rule guard distinguishes grep exit 0/1/other instead of treating any
  non-zero as clean; the AI-tool-reference check was dead until now — see report
- [x] Remediation: container reuse disabled; Flyway assertion proves migration ran in
  this run (`installed_on` after JVM start), not a leftover row
- [x] Remediation: `container_name` removed from compose; host ports parameterized
  (`POSTGRES_PORT`, `KAFKA_PORT`) so two stacks run side by side under distinct
  `COMPOSE_PROJECT_NAME` values
- [x] Remediation: Maven wrapper (`mvnw`, `mvnw.cmd`, `.mvn/wrapper/`) committed; CI and
  README use `./mvnw`
- [x] Cleanup: redundant `testcontainers-bom` import removed from pom.xml,
  `PostgreSQLContainer` parameterized, PROGRESS.md sixth commit sha filled in

## In progress
- Nothing; phase deliverables and remediation are complete pending this session's commits

## Blocked / open questions
- None. `spring-boot-testcontainers` and `spring-boot-flyway` were flagged in the prior
  session; `CLAUDE.md` (merged in from `main`) now lists both as approved exceptions.
  Not re-raised.

## Next step
- Push `phase-0-skeleton`; do not merge into `main` (per current `CLAUDE.md`, merges are
  user-only). Wait for the user to review and merge.
