# Progress

**Current phase:** 1b — Domain, service, API
**Branch:** phase-1b-service
**Last updated:** 2026-09-04 18:40

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

## Corrections applied after the phase (requested before phase 2)
- [x] V7: a transfer's accounts must share a currency, rejected with 422 currency_mismatch
  before any balance moves. Entries of -1000 TRY and +1000 USD sum to zero and each match
  their own account, so I1, I2, I3 and I7 all pass while money is created — fb7908d
- [x] `crossCurrencyTransferRejected` asserts the 422, both balances unchanged and no rows
  written. Break proof done: with the check deleted the same request returns 201 CREATED — fb7908d
- [x] Confirmed and fixed: `EntryRepository.insert` takes the currency from the account being
  posted to. It used the `Money.CURRENCY` constant, which happened to agree only because every
  account is TRY. `entriesCarryTheCurrencyOfTheirAccount` posts between two USD accounts — fb7908d
- [x] `allow_negative` is now a generated column, `GENERATED ALWAYS AS (account_type = 'EQUITY')`,
  and is gone from the create-account request. PostgreSQL 16 cannot convert a column in place, so
  V6 drops and re-adds it — c1cf61d
- [x] `./mvnw -B verify` green: 45 tests, 1 m 01 s. ci/check-rules.sh exits 0

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
- V7 is enforced in the service only, like V1 to V3. Whether a transaction whose entries span
  two currencies should also be refused by a trigger is a question for the user; not guessed at
- PHASES.md still prints the phase-1a `accounts` table with a settable `allow_negative`. The
  plan document was left as written; V6 is the change of record

## Next step
- Push phase-1b-service; do not merge into main (merges are user-only). Wait for the
  audit before phase 2
