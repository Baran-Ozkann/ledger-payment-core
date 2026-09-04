# Progress

**Current phase:** 2 — Idempotency and concurrency
**Branch:** phase-2-concurrency (branched from phase-1b-service, which is not merged yet)
**Last updated:** 2026-09-04 19:35

## Done in this phase
- [x] `V8__idempotency.sql`: idempotency_keys with uq_client_key UNIQUE (client_id, idem_key) and
  the valid_status CHECK. The plan calls it V3; V3 to V7 were taken, so it is V8
- [x] Store: `IdempotencyRepository.claim` is INSERT ... ON CONFLICT DO NOTHING RETURNING id, which
  yields an empty Optional rather than raising. `complete` runs in the same transaction as the
  ledger write. Schema test covers the constraint itself, not the protocol over it
- [x] `RequestHashFilter` reads the body once, hashes SHA-256 over method + path + canonicalized
  body, and hands it on as a request attribute. Object keys sorted, arrays left alone
- [x] Protocol in `LedgerService.idempotently`: claim wins → execute and store the response; claim
  lost → 200 with the stored body, 422 idempotency_key_reuse on a different hash, 409 on IN_PROGRESS
- [x] `Idempotency-Key` and `X-Client-Id` required on all three mutating endpoints (V5, V6).
  `ClientId` was folded into `IdempotencyRequest.of`, which checks both
- [x] Ordered locking: `SELECT ... FOR UPDATE` on both accounts in ascending internal id order
  before either row is written. Insufficient funds is still the WHERE clause of the debit UPDATE
- [x] Seven concurrency tests, none @Transactional, all released by one CountDownLatch
- [x] Break proof 1: fixed from-then-to locking → 87 of 100 transfers died on deadlock detected
- [x] Break proof 2: no ON CONFLICT and no unique constraint → 100 transactions instead of 1
- [x] `bidirectionalNoDeadlock` run 5 consecutive times: zero deadlocks, all green
- [x] `./mvnw -B verify` green: 60 tests, 1 m 10 s. ci/check-rules.sh exits 0

## In progress
- Nothing; the phase deliverables are complete

## Blocked / open questions
- `response_code` is written and never read: a replay answers 200 as the phase specifies, so the
  stored 201 has no reader. Drop the column, or replay with the stored code?
- `transaction_id` on idempotency_keys is written and never read. Phase 4 reversal may want it
- The IN_PROGRESS branch (409) is unreachable while the claim commits with the ledger write: a
  concurrent duplicate blocks on the unique index and then sees COMPLETED. Keep it as specified,
  commit the claim separately for a fast 409, or delete the branch?
- Nothing reads `expires_at`; the sweeper is in docs/future.md and the 24 hour window is a guess
- `sameKeyDifferentEndpoint` uses transfer then funding, because reversal belongs to phase 4. It
  tests the same property: the path is part of the hash

## Next step
- Push phase-2-concurrency; do not merge into main (merges are user-only). Wait for the audit
