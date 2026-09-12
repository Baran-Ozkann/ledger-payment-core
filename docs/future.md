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

## The metrics endpoint still dies under saturation — the split port was not the fix

Under the hot-account scenario at 200 VUs and above, the application stopped answering
`/actuator/prometheus` altogether: 49 of 133 scrapes failed, all from the moment the 200 VU step
began, with `scrape_duration_seconds` climbing to the 5 s timeout. The signal that says "the system
is saturated" was the first thing saturation took away.

Phase 5 could report the run anyway only because the database-side sampler is a psql session inside
the container that owes the application nothing. That redundancy was luck of the harness design, not
a property of the system.

Phase 5 read the cause as thread starvation — all 200 Tomcat workers blocked on ten pool
connections, none left to serve the scrape — and `management.server.port: 8081` gave actuator its
own connector, and with it its own thread pool. That was the usual fix for the stated cause, and it
went in without being re-measured.

**It has now been re-measured, and it does not work.** Scenario H re-run on 2026-09-12 under the
split connector: **44 of 133 scrapes failed**, against 49 of 133 before, with the first failure at
offset 423.5 s where the 200 VU step begins at 420 s. Same boundary, same timeout, five fewer
failures out of 133 — noise, not a repair.
`load/results/h-read-committed-split-connector.json`, written up in
[load/RESULTS.md](../load/RESULTS.md) as a second observation beside the original.

The reading that survives the second run is different from the first one. The connectors really are
separate and the request threads really were available — the API served 8 995 requests during the
200 VU step and dropped none of them. What the scrape cannot get is a **database connection**.
`ledger_outbox_pending` and `ledger_outbox_lag_seconds` are a `COUNT(*)` and a `min(created_at)`
over the outbox, evaluated inside the scrape, and they queue for the same ten-connection pool as
every transfer. At the last scrape that returned: 10 connections active, 92 threads pending on the
pool. A scrape costs two pool acquisitions, so it costs about two transfer latencies — 100 ms at
10 VUs, 1.12 s at 50, 2.44 s at 100 — and 200 VUs is simply where two of them stop fitting inside
Prometheus's 5 s timeout. Moving the connector could not have helped, because the connector was
never what the scrape was waiting for.

So the split port stays, on its own merits: it is what lets Prometheus reach the application from a
container without the money-moving API being on the network. It is just not a fix for this.

What would fix it, cheapest first:

- **Stop doing database work inside a scrape.** The relay already takes a connection every 200 ms.
  Have it publish the two outbox figures into plain in-memory gauges and let the scrape read the
  last value it left. `/actuator/prometheus` then becomes a pure in-memory render that cannot queue
  behind anything, and the endpoint keeps answering no matter how saturated the pool is.

  Be honest about what this does and does not buy: under saturation the relay is queueing for the
  same pool as everything else — it drains at a fourteenth of the write rate, which is the finding
  directly above — so the published figures go stale by seconds rather than by 200 ms exactly when
  they are most interesting. That is still the right trade. A gauge that is a few seconds behind is
  a measurement; a scrape that times out is not one, and it takes `process_cpu_usage`,
  `jvm_threads_live_threads` and the pool metrics themselves down with it — none of which touch the
  database at all, and all of which were lost at 200 VUs for the sake of two that do.
- **A connection the management side does not share**, one or two, on a second `DataSource`. It
  keeps the gauges exact and live, and costs the ledger one or two of the connections it is already
  short of — the same trade the relay's own pool would make, listed above.
- **Raise `scrape_timeout`** above the saturated scrape duration. This is the one that looks like a
  fix and is not: it buys a scrape that answers in eight seconds instead of failing at five, on a
  5 s interval, while doing nothing about a metrics endpoint that gets slower in proportion to how
  busy the system is.

The first is the one to do. It is a few lines in `OutboxMetrics` and it removes the coupling rather
than paying for it.

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
