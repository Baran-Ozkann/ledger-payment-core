# Future work

Things that were deliberately not built, with the reason. Nothing here is a promise.

## Idempotency key expiry sweeper

`idempotency_keys.expires_at` is stamped 24 hours ahead by the database clock and indexed by
`idx_idem_expiry`, but nothing reads it yet: phase 2 owns the protocol, not the housekeeping. Until
a sweeper exists the table only grows, and a key is honoured forever rather than for its stated
lifetime.

A sweeper has to delete only rows past `expires_at`, and deleting a completed row means a retry of
that request would execute a second time — so the retention window is a product decision, not a
cleanup detail. The 24 hour value itself is a placeholder chosen to have something concrete in the
column; it is raised as an open question in the phase 2 report.

## Deadlock metric

Ordered locking is proven by test, not observed in production. Phase 4 owns metrics; the deadlock
counter belongs there and is expected to stay at zero.
