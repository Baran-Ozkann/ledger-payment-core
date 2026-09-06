# Progress

**Current phase:** 4 — Reversal, reconciliation, observability
**Branch:** phase-4-recon-observability (branched from main, which now carries phases 0 to 3)
**Last updated:** 2026-09-06

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

- [x] Metrics: `ledger_transfer_duration_seconds` (histogram) and `ledger_transfer_total`, both
  tagged `operation` and `result`; `ledger_idempotency_hit_total`; `ledger_deadlock_retry_total`,
  which stays at zero; `ledger_outbox_pending` and `ledger_outbox_lag_seconds` as gauges. The
  wrapper sits in the controller, outside the transaction, so a failure at commit is counted
- [x] Six metric tests, one of which reads `/actuator/prometheus` and asserts every metric name the
  dashboard will query, because Micrometer renames on the way out

- [x] Tracing: the request's W3C traceparent is written onto the outbox row inside the transfer
  transaction (`V11`), and the relay republishes inside it, so the producer span is a child of the
  request rather than the root of something unrelated. `spring.kafka.template.observation-enabled`
  and its listener counterpart carry it the rest of the way, over the record's own headers
- [x] `TracePropagationTest`: the caller supplies the traceparent, so the trace id is known before
  the request is made and every hop afterwards has to agree with a value decided outside the
  application. Break proof recorded - with the producer observation off, the consumer lands in a
  different trace and the test says so

- [x] Prometheus, Tempo and Grafana in compose, with the datasources and the dashboard provisioned
  from `ops/` rather than drawn in the UI. The application stays on the host, which is why the
  scrape target is `host.docker.internal:8080`; putting it in compose belongs to phase 6
- [x] `docs/images/trace-http-to-consumer.png`: one trace, seven spans, the HTTP request at the
  root and a consumer span at the end of each of the transfer's two entries

## In progress
- Nothing. The phase is complete; the branch is ready for audit

## Blocked / open questions
- `spring-boot-starter-opentelemetry` is not on the approved dependency list in CLAUDE.md. Phase 4
  names Micrometer Tracing and OpenTelemetry as a deliverable and this is Boot 4's single starter
  for both, so it was treated as covered by the phase definition. It needs sign-off
