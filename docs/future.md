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

## ADR-003 material: SKIP LOCKED, and the ordering it gives up

The relay takes its batch with `ORDER BY id ... FOR UPDATE SKIP LOCKED`. What that buys is that a
second relay instance would step over a batch another one already holds instead of queueing behind
it, and that a relay which dies mid-batch blocks nobody: its locks go with its transaction and the
rows are picked up on the next tick.

What it gives up is global ordering. Two relays would publish overlapping id ranges concurrently,
so an event with a lower id can reach the broker after one with a higher id. Even one relay gives
up ordering across the topic, because three partitions are read independently.

What survives is ordering per account, and it survives for two reasons that both have to hold:

- **A single relay instance.** One publisher, sending in `ORDER BY id` and waiting for each send,
  means the broker sees one account's events in the order they were written.
- **The partition key is the aggregate id.** All events of one account take one partition, and a
  partition is ordered. This is why the event is per entry rather than per transaction: a
  transaction has two accounts and no single key.

Per-account ordering is the guarantee worth having; a consumer building an account's history needs
its events in order and does not care where another account's events sit relative to them. A
horizontally scaled relay is out of scope for this project, and it is the thing that would break
the first of those two reasons. Anyone lifting that restriction has to shard the relay by
`hashtext(aggregate_id)` so that one account is only ever published by one instance.

**Rejected: a high-water-mark cursor** (`WHERE id > last_seen`), which needs no locking and no
`published_at` write at all. It is unsound. A `BIGSERIAL` value is handed out before the
transaction commits, so a row with a lower id can become visible after a row with a higher id has
been read and the cursor has moved past it. That event is then never published, and nothing in the
system ever notices: there is no marker left to find it by. The `published_at IS NULL` marker
cannot be outrun, which is why it is worth its index and its second write.

## Consumed event retention — after Phase 4

`consumed_events` grows by one row per event per consumer group and nothing prunes it. Phase 4 adds
retention for `idempotency_keys` and `outbox_events`, and this table belongs in the same
conversation, but it cannot use the same rule: deleting a row means the next redelivery of that
event is applied a second time. It can only be pruned past the point where redelivery is
impossible, which is the broker's own retention window, so the two settings have to be decided
together rather than a day being picked for it.
