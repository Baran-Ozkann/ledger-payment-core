# Future work

Things that were deliberately not built, with the reason, plus material for decision records that
still have to be written. Nothing here is a promise.

## Idempotency key expiry sweeper — Phase 4

`idempotency_keys.expires_at` is stamped 24 hours ahead by the database clock and indexed by
`idx_idem_expiry`, but nothing reads it yet. The window stays at 24 hours; the sweeper that acts on
it lands in Phase 4 with the rest of the scheduled work. Until then the table only grows and a key
is honoured for longer than it says.

The sweeper deletes only rows past `expires_at`. Deleting a completed row means a retry of that
request executes a second time, so the retention window is a decision about how late a client may
retry, not a cleanup detail.

## ADR-005 material: the idempotency claim commits with the ledger write

The claim, the ledger write and the stored response are one transaction. That single choice is what
removed the `status` column, the `IN_PROGRESS` state and the `409 request_in_progress` response in
`V9__drop_idempotency_status.sql`: a row another request can see is always a finished one, because
an attempt that fails takes its own claim down with it. There is no in-progress state to observe.

The trade, stated plainly:

- **Given up.** A concurrent duplicate cannot be answered immediately. It blocks on the unique index
  until the first attempt commits or rolls back, and only then learns whether it is a replay or the
  owner of the key. A fast 409 would need the claim committed in a transaction of its own.
- **Bought.** No key is ever stranded. Committing the claim separately would mean a crash between
  claiming and writing leaves a row claimed and never completed, so that request could never be
  retried under its own key — an outcome no client can recover from, on a path that only exists to
  make retries safe.
- **Also bought.** A rejected transfer releases its key with the money it did not move, so a client
  that fixes the request and retries it with the same key is not told the key is spent.

The blocking is bounded by the length of a ledger transaction, which is two row locks and four
statements. The stranded-key failure is unbounded and needs an operator. That asymmetry is the whole
argument, and it should survive into ADR-005 as written.

## Deadlock metric — Phase 4

Ordered locking is proven by test, not observed in production. Phase 4 owns metrics; the deadlock
counter belongs there and is expected to stay at zero.
