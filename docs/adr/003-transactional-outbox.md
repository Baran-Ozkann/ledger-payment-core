# ADR-003 — A transactional outbox, not CDC and not a dual write

## Context

A transfer has to produce an event per entry, and that event has to agree with what committed:
every committed transfer produces exactly one event per entry eventually, and a transfer that
rolled back produces none. The database and the broker are two systems with no shared transaction
between them, so "agree" is the whole problem.

## Decision

The event is a row. `LedgerService.announce` inserts into `outbox_events` inside the transfer's own
transaction, keyed by the account's public id, one row per entry. A relay (`OutboxRelay`) polls
every 200 ms in a transaction of its own, takes a batch of 100 with

```sql
SELECT ... FROM outbox_events WHERE published_at IS NULL ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED
```

publishes each one, waits for the send, and marks it published before moving to the next. Delivery
is at-least-once; `consumed_events` makes the second delivery a no-op, inserted in the same
transaction as the projection write.

## Consequences

The row cannot disagree with the entries it was written beside, because it commits or rolls back
with them. That is the only guarantee here that is free.

Ordering survives per account, and it survives for two reasons that both have to hold:

- **A single relay instance.** One publisher, sending in `ORDER BY id` and awaiting each send.
- **The partition key is the aggregate id.** All events of one account land on one partition, and
  a partition is ordered. This is why the event is per entry rather than per transaction: a
  transaction has two accounts and therefore no single key.

`SKIP LOCKED` is there so that a relay which dies mid-batch blocks nobody — its locks go with its
transaction and the rows are taken on the next tick. It also means a second instance would step
over a batch rather than queue behind it, which is what would break the first of those two reasons.
A horizontally scaled relay is out of scope by name; anyone lifting that has to shard by
`hashtext(aggregate_id)` first, so one account is only ever published by one instance.

What it costs, measured rather than estimated. Under the phase 5 ramp the ledger wrote about 700
events per second and the relay published about **50**, ending eleven minutes and 442 718 rows
behind with `ledger_outbox_lag_seconds` at 602. Nothing is lost — `published_at IS NULL` cannot be
outrun, and the backlog drains once load stops — but a consumer reading the projection is ten
minutes stale for as long as the load lasts. The cause is budget, not design: the relay competes
for one of the same ten pool connections against 190 queued request threads.

## Rejected alternatives

**A dual write: publish to Kafka from inside the transfer.**

Two failures, and both are silent. A send that succeeds followed by a rollback announces a transfer
that never happened, and the consumer applies it — money that does not exist in the ledger now
exists in every read model derived from it, and no query against the ledger can find the
discrepancy because the ledger is correct. A commit followed by a broker timeout loses the event
permanently: the transfer is durable, the announcement is gone, and nothing anywhere records that
it was owed. The outbox turns both into a row that is either there or not there, along with the
money.

It also fails transfers that are fine. A broker that is slow or down makes the transfer slow or
fail, so the ledger's availability becomes the broker's availability, for the sake of a
notification.

**Change data capture: Debezium reading the write-ahead log.**

It publishes rows, not events. A consumer would receive `ledger_entries` tuples carrying the
internal `account_id BIGINT` and nothing else — no aggregate identity it can use as a partition
key, no event type, no `traceparent`. Recovering the account's public id means a query back into
the ledger for every event, which is exactly the coupling the events exist to remove.

The deeper failure is that the table becomes the wire format. `V6__derived_allow_negative.sql`
drops and re-adds a column; under CDC that migration is a breaking change to every consumer, and
the ledger's schema can no longer be refactored without a release plan for systems it does not know
about. An outbox row is a payload written deliberately, with a version the ledger controls.

It also brings a Kafka Connect cluster to run, configure and monitor, for a project whose scope
lock forbids a horizontally scaled relay in the first place.

**A high-water-mark cursor (`WHERE id > last_seen`) instead of the `published_at` marker.**

Tempting because it needs no locking and no second write per event. It is unsound. A `BIGSERIAL`
value is handed out before the transaction commits, so a row with a lower id can become visible
*after* a row with a higher id has been read and the cursor has moved past it. That event is then
never published, and nothing in the system ever notices, because there is no marker left to find it
by. The `published_at IS NULL` marker cannot be outrun, which is what its index and its second
write are worth.
