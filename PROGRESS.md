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

## In progress
- Nothing

## Next up in this phase
- Reconciliation job, drift metric, no auto-correct
- Retention jobs for idempotency keys and published outbox rows
- Transfer, idempotency, deadlock and outbox metrics; Prometheus endpoint
- Tracing through the outbox and Kafka
- Prometheus, Grafana and Tempo in compose; dashboard JSON under ops/grafana/

## Blocked / open questions
- Nothing yet
