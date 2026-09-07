# Progress

**Current phase:** 5 — Load testing
**Branch:** phase-5-load (branched from main, which carries phases 0 to 4)
**Last updated:** 2026-09-07

## Done in this phase
- [x] Prometheus retention raised from 24h to 30d. A ramp is eleven minutes and gets read
  afterwards; at 24h the early steps roll off before anyone compares them to the late ones
- [x] `ConcurrencyPolicy` plus the `serializable` profile: the ADR-004 alternative made runnable.
  The profile sets `TRANSACTION_SERIALIZABLE`, drops the explicit `FOR UPDATE` locks and bounds
  retries at five. The default is unchanged - one attempt means the loop runs once and rethrows,
  so `ledger_deadlock_retry_total` still means "ordered locking failed somewhere"
- [x] `SerializableConcurrencyTest`: asserts the profile really is serializable, that ordered
  locking really is off, and that sixteen concurrent debits of one account still add up. The
  assertion is written against the successes the run had, because retry exhaustion is a real
  outcome of that configuration and a test demanding it never happen would flake
- [x] `load/k6/`: scenario U and scenario H over a shared module. One expression differs between
  them, so the gap between the two runs is attributable to the shared row and nothing else
- [x] `load/seed.py`: ten thousand accounts created and funded through the API, never by INSERT -
  seeded with SQL, the balances would not come from entries and I2/I3 would be false before the
  first measured request
- [x] `load/run.py`: reseed, start, **confirm the database from the container's side**, ramp,
  sample `pg_stat_activity` every two seconds, collect the Prometheus window, stop
- [x] `load/charts.py`: results JSON to SVG, no plotting library, so a chart is reviewable in a diff

## Measurement fidelity notes
- The compose PostgreSQL is published on **5433**. A native PostgreSQL 18 service is running on
  this machine on 5432 and would answer a misdirected run; it does not share the `ledger`
  credentials, but the check does not rely on that. Every run names its connections and asks the
  container how many by that name it is serving, and the answer is stored in the result file
- k6, the application, PostgreSQL and Kafka all share one 6-core laptop. This is stated in
  RESULTS.md rather than worked around
- Two problems found and fixed while building the harness, both of which would have produced
  wrong numbers silently: refused connections were being recorded as 0 ms samples (dragging the
  400 VU median to zero), and the in-container sampler survived `kill()` of its exec client and
  went on querying into the next run

## In progress
- The four measured runs (u/h x read-committed/serializable), then charts and `load/RESULTS.md`

## Blocked / open questions
- Nothing new. The `spring-boot-starter-opentelemetry` sign-off from phase 4 is still outstanding
