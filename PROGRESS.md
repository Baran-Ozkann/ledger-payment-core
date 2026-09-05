# Progress

**Current phase:** 4 — Reversal, reconciliation, observability
**Branch:** phase-4-recon-observability (branched from main, which now carries phases 0 to 3)
**Last updated:** 2026-09-05

## Done in this phase
- [x] `POST /v1/transfers/{publicId}/reversals`: a new REVERSAL transaction carrying the original's
  entries with their signs flipped. The original is never touched, `uq_single_reversal` refuses the
  second one, and the accounts are locked in ascending internal id order like every other pair
- [x] The flipped entries go through the same conditional UPDATE an ordinary debit uses, so a
  reversal whose counterpart has spent the money is refused rather than driving it negative
- [x] Four reversal tests: `reversalCreatesCompensatingEntries`, `reversalFailsOnInsufficientFunds`,
  `reversalOfUnknownTransactionIsNotFound`, and `doubleReversalRejected` with 20 concurrent threads

- [x] `ReconciliationJob`: every five minutes, I3 per account in id-range batches of 10 000 and I2
  across the ledger. Drift increments `ledger_balance_drift_total{scope}` and is logged with the
  account ids; nothing is corrected. Prometheus endpoint exposed on the actuator
- [x] Three reconciliation tests: `reconciliationDetectsDrift`, `reconciliationDoesNotAutoFix` and
  a quiet pass over a ledger that agrees. The I2 alarm branch is unreachable by design - the
  deferred trigger refuses an unbalanced transaction - so only its quiet path is exercised

- [x] `IdempotencyKeyCleanupJob` and `OutboxArchivalJob`: daily, batched at 10 000 rows, each batch
  committing on its own so the loop never holds every lock it took. Both count rows into
  `ledger_cleanup_rows_deleted_total{job}`. The archival filters on `published_at`, never
  `created_at`, and `unpublishedOutboxEventsNeverArchived` is the test that says so

## In progress
- Nothing

## Next up in this phase
- Transfer, idempotency, deadlock and outbox metrics; Prometheus endpoint
- Tracing through the outbox and Kafka
- Prometheus, Grafana and Tempo in compose; dashboard JSON under ops/grafana/

## Blocked / open questions
- Nothing yet
