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

- [x] All four runs measured, `load/charts/*.svg` generated and `load/RESULTS.md` written.
  U saturates at 50 VUs / 374 tx/s on the connection pool; H reaches 68 tx/s, 5.5x slower, on one
  row. SERIALIZABLE is 10% faster without contention and refuses 59% of requests with it

## Security work, requested mid-phase and outside the phase definition
- [x] Compose published every port on 0.0.0.0 and [::]; now 127.0.0.1 only, verified after applying
- [x] The request body was buffered unbounded before the idempotency hash. A single 400 MB POST
  against a 256 MB heap produced an OutOfMemoryError from an endpoint needing no credentials.
  Capped at 64 KB with a 413, `RequestSizeTest` covers both halves
- [x] CI token restricted to `contents: read`; the load harness validates `--name` before it
  reaches a SQL literal

## In progress
- Nothing. The phase is complete; the branch is ready for audit

## Blocked / open questions
- The `spring-boot-starter-opentelemetry` sign-off from phase 4 is still outstanding
- **The application connects to PostgreSQL as a superuser.** It can disable the triggers enforcing
  I1, I5, I7 and I8 in one statement, demonstrated and rolled back. CLAUDE.md specifies I5 as
  "Trigger that RAISEs + DB role grants" and the grants half does not exist. The fix is two roles
  and it changes how every connection in the project authenticates, so it needs its own change and
  its own test pass rather than being appended here. Written up in `docs/future.md`
- **The application listens on 0.0.0.0:8080** with no authentication. Binding it to loopback breaks
  the phase 4 Prometheus scrape; moving the app into compose fixes both and is already the phase 6
  plan. Written up in `docs/future.md`
