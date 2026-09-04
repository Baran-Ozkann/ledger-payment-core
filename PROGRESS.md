# Progress

**Current phase:** 1b — Domain, service, API
**Branch:** phase-1b-service
**Last updated:** 2026-09-04 12:45

## Done in this phase
- [x] Domain: Money (minor units in a long, exact arithmetic, MAX_AMOUNT), AccountType,
  TxType, Account, LedgerTransaction, LedgerEntry, LedgerError, LedgerException — 55665f6
- [x] Store: AccountRepository, TransactionRepository, EntryRepository over JdbcClient with
  explicit SQL. Insufficient funds is the WHERE clause of the debit UPDATE — 35e2d55
- [x] Service: LedgerService.transfer and .fund in a single @Transactional block, V1 to V3
  checked before any read or write, both accounts touched in ascending internal id
  order — 35e2d55
- [x] API: POST/GET /v1/accounts, GET /v1/accounts/{id}/entries with cursor pagination,
  POST/GET /v1/transfers, POST /v1/funding, RFC 7807 problem details, X-Client-Id
  required on mutating endpoints, Jackson refusing decimal amounts — 8b57034
- [x] Property tests: I2 and I3, 1000 tries each, jqwik on its own database — 2b0c741
- [x] `./mvnw -B verify` green: 39 tests, 54 s. ci/check-rules.sh exits 0
- [x] Exit criterion checked by hand: account created, funded and transferred from over
  curl against the Compose PostgreSQL; V1, V3, V4 and V5 rejections observed there too

## In progress
- Nothing; the phase deliverables are complete

## Blocked / open questions
- Money has no `minus`: balance arithmetic lives in the SQL conditional UPDATE, so
  subtractExact has no call site. Raised in the report rather than shipping an unused
  method
- POST /v1/funding is not in the phase's endpoint list, but the exit criterion requires
  funding an account over curl. Raised in the report
- V6 (Idempotency-Key required) is deliberately not implemented; phase 1b is defined as
  "no idempotency yet" and phase 2 owns it

## Next step
- Push phase-1b-service; do not merge into main (merges are user-only). Wait for the
  audit before phase 2
