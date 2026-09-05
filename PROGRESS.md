# Progress

**Current phase:** 3 — Outbox and event publishing
**Branch:** phase-3-outbox (branched from main, which now carries phases 0 to 2)
**Last updated:** 2026-09-05

## Done in this phase
- [x] `V10__outbox.sql`: `outbox_events` with a partial index on unpublished rows, `consumed_events`
  keyed by (consumer_group, event_id), and `account_activity` as the read model. The plan calls the
  migration V4; V4 to V9 are taken, so it is V10
- [x] The transfer transaction writes one outbox row per entry, keyed by the account's public id.
  Per entry rather than per transaction, because the account is the aggregate and the aggregate id
  has to be the partition key for per-account ordering to mean anything
- [x] `OutboxRelay`: scheduled every 200 ms, its own transaction, `ORDER BY id ... FOR UPDATE SKIP
  LOCKED` in batches of 100. Each event is sent, waited on, then marked. A failure records the
  error against the row and stops the batch rather than reordering past it
- [x] Producer `acks=all`, `enable.idempotence=true`, key = aggregate id. Topic declared with three
  partitions, so the ordering the key buys is not an accident of having nowhere else to go
- [x] `AccountActivityProjection`: the `consumed_events` insert and the projection write are one
  transaction; ack mode RECORD, so the offset moves only after that transaction commits
- [x] Six tests: `outboxWrittenAtomically`, `outboxNotWrittenOnRollback`, `relayPublishesAndMarks`,
  `relayRetriesUnpublished`, `consumerReplayIdempotent`, `perAccountOrdering`
- [x] Break proof: dedup insert removed → `consumerReplayIdempotent` fails with net 2400 instead of
  1500, the repeat applied twice. Restored → green
- [x] Kafka tests tagged `@Tag("kafka")` and split into their own CI job with a surefire group
  filter. A plain `./mvnw -B verify` still runs everything
- [x] `./mvnw -B verify` green: 66 tests. Non-Kafka job 2 m 15 s, Kafka job 28 s. check-rules.sh
  exits 0

## Decisions worth remembering
- `relayRetriesUnpublished` reserves an id from the sequence, publishes later rows, and only then
  inserts the reserved id. A cursor-based relay would have moved past it; the NULL marker cannot be
  outrun. That is the pitfall the test exists for, written up in docs/future.md for ADR-003
- `consumerReplayIdempotent` produces the repeat by putting `published_at` back to NULL, which is
  what a crash between the send and the mark leaves behind, and fences it with a second transfer on
  the same key so that "the repeat was handled" is observable rather than assumed
- The event type is not on the wire. It is a column, read into the relay's failure log; one topic
  carries one event type in this phase, and an unread header would be structure nothing reads

## In progress
- Nothing; the phase deliverables are complete

## Blocked / open questions
- `spring-boot-kafka` was added beyond the approved dependency list. It is the Boot 4 module split
  that made `spring-boot-flyway` necessary, and Phase 3 cannot use Kafka without it. Flagged in the
  report for approval after the fact
- CI durations are from local runs of the two exact CI commands. GitHub Actions has not run this
  branch yet

## Next step
- Push phase-3-outbox; do not merge into main (merges are user-only). Wait for the audit
