# Future work

Things that were deliberately not built, with the reason. Nothing here is a promise.

Material that was gathered here for decision records has moved into the records themselves —
[ADR-003](adr/003-transactional-outbox.md) for the relay's ordering trade and the high-water-mark
cursor, [ADR-005](adr/005-idempotency-in-transaction.md) for the idempotency claim — and the phase 5
security findings have been fixed rather than deferred: the application connects as `ledger_app`
(`V12__least_privilege_app_role.sql`), the API binds loopback, and it now runs as a compose service
so no host port has to be opened for it at all.

## Consumed event retention

`consumed_events` grows by one row per event per consumer group and nothing prunes it. Phase 4 added
retention for `idempotency_keys` and `outbox_events`, and this table belongs in the same
conversation, but it cannot use the same rule: deleting a row means the next redelivery of that
event is applied a second time. It can only be pruned past the point where redelivery is impossible,
which is the broker's own retention window, so the two settings have to be decided together rather
than a day being picked for it.

## Exemplars: from a slow histogram bucket to the trace that filled it

Micrometer can attach a trace id to a Prometheus histogram sample, so clicking the p99 bucket in the
latency panel opens the trace of a request that landed in it. It needs `exemplars` enabled on the
Prometheus side and `traceToMetrics` wiring in the Grafana datasource, and it is the natural next
step now that the trace and the metric exist for the same request. It was left out of Phase 4
because the phase's deliverable is that both signals exist and are correct; joining them is a
convenience on top, and one more thing to get wrong in a dashboard nobody has used yet.

## The relay's drain rate under load — measured in Phase 5

Phase 5 measured the relay publishing about **50 events per second** while the ledger wrote about
**700**, ending an eleven-minute ramp 442 718 rows behind with `ledger_outbox_lag_seconds` at 602.
The events are not lost — `published_at IS NULL` cannot be outrun and the backlog drains once the
load stops — but a consumer reading the projection is ten minutes stale for as long as the load
lasts, and nothing in the system says so except that gauge.

The cause is not the relay's design so much as its budget. It is one scheduled method taking a
hundred rows and awaiting each send in turn, and it competes for a connection out of the same pool
of ten as the request threads. At 400 VUs there are 190 request threads queued in front of it, so it
gets roughly one slot's worth of the pool and publishes at a fourteenth of the arrival rate.

Three things could move it, in increasing order of how much they give up:

- **A connection pool of its own.** The cheapest, and it changes no guarantee: the relay stops
  queueing behind request threads. It also takes connections away from the ledger, which on this
  hardware is the thing already at its ceiling.
- **Pipelining the batch** instead of awaiting each send. Kafka's producer is asynchronous and the
  waiting is what makes the batch slow. Per-account ordering survives only if the futures of one
  aggregate id are still resolved in order, which is more bookkeeping than it sounds.
- **A horizontally scaled relay**, which is out of scope by name and would need sharding by
  `hashtext(aggregate_id)` first — see [ADR-003](adr/003-transactional-outbox.md).

None of it was done in Phase 5, which measures rather than optimises.

## Whether the split management port survives saturation — untested since Phase 5

Under the hot-account scenario at 200 VUs and above, the application stopped answering
`/actuator/prometheus` altogether: 49 of 133 scrapes failed, all from the moment the 200 VU step
began, with `scrape_duration_seconds` climbing to the 5 s timeout. All 200 Tomcat worker threads
were blocked waiting for one of ten pool connections, so no thread was left to serve the scrape. The
signal that says "the system is saturated" was the first thing saturation took away.

Phase 5 could report the run anyway only because the database-side sampler is a psql session inside
the container that owes the application nothing. That redundancy was luck of the harness design, not
a property of the system.

`management.server.port: 8081` has since given actuator its own connector, which is the usual fix
and is why the scrape now has a thread pool of its own. **It has not been re-measured under the same
load**, so what exists today is a plausible fix rather than a demonstrated one. Re-running scenario
H and counting failed scrapes is the whole test, and it costs one eleven-minute ramp.

## Mutation testing — the optional Phase 6 item, dropped

The roadmap offers PIT (`pitest-maven`) against `domain`, `service` and `store` as the one optional
item in the plan, to turn "the tests protect the invariants" into a number, and says to drop it first
if the schedule slips. It was dropped.

The reason is more than schedule. Almost every assertion in this suite runs against a real
PostgreSQL, so a mutation run means restarting containers for each surviving mutant and the wall
clock is measured in hours rather than the minutes PIT is designed around. It would also score the
wrong layer: the mechanisms this project is about are triggers, constraints, grants and index
definitions, and a Java mutation operator cannot reach any of them. The break proofs are the
equivalent evidence for those, and they are recorded per mechanism rather than as a percentage.

If it is ever added, the honest scope is `domain` and the pure branches of `service` — `Money`, the
V1 to V7 guards, `EntriesPage.of` — with the integration tests excluded, and the number reported as
covering that slice rather than the system.
