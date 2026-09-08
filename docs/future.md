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

## The application as a compose service — Phase 6

`docker compose up` brings up PostgreSQL, Kafka, Tempo, Prometheus and Grafana, but not the ledger.
It runs on the host, which is why Prometheus scrapes `host.docker.internal:8080` and why the OTLP
endpoint is `localhost:4318`. That is the right trade during development, where the application is
the one thing being restarted constantly, and the wrong one for the Phase 6 exit criterion, which
is `docker compose up` followed by a single `curl`. Adding the service means a Dockerfile, a
profile that points at the service names instead of localhost, and a `depends_on` on the two
healthchecks that already exist.

## Exemplars: from a slow histogram bucket to the trace that filled it

Micrometer can attach a trace id to a Prometheus histogram sample, so clicking the p99 bucket in
the latency panel opens the trace of a request that landed in it. It needs `exemplars` enabled on
the Prometheus side and `traceToMetrics` wiring in the Grafana datasource, and it is the natural
next step now that the trace and the metric exist for the same request. It was left out of Phase 4
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
of ten as the request threads. At 400 VUs there are 190 request threads queued in front of it, so
it gets roughly one slot's worth of the pool and publishes at a fourteenth of the arrival rate.

Three things could move it, in increasing order of how much they give up:

- **A connection pool of its own.** The cheapest, and it changes no guarantee: the relay stops
  queueing behind request threads. It also takes connections away from the ledger, which on this
  hardware is the thing already at its ceiling.
- **Pipelining the batch** instead of awaiting each send. Kafka's producer is asynchronous and the
  waiting is what makes the batch slow. Per-account ordering survives only if the futures of one
  aggregate id are still resolved in order, which is more bookkeeping than it sounds.
- **A horizontally scaled relay**, which is out of scope by name and would need sharding by
  `hashtext(aggregate_id)` first — see the ADR-003 material above.

None of it was done in Phase 5, which measures rather than optimises. The number belongs in the
README's honest-limits section either way.

## The metrics endpoint is unavailable exactly when it matters — Phase 5

Under the hot-account scenario at 200 VUs and above, the application stopped answering
`/actuator/prometheus` altogether: 49 of 133 scrapes failed, all of them from the moment the 200 VU
step began, with `scrape_duration_seconds` climbing to the 5 s timeout. All 200 Tomcat worker
threads were blocked waiting for one of the ten pool connections, so no thread was left to serve
the scrape. The signal that says "the system is saturated" is the first thing saturation takes
away.

Phase 5 could report the run anyway only because the database-side sampler is a psql session inside
the container that owes the application nothing. That redundancy was luck of the harness design,
not a property of the system.

The usual fix is a separate management port with its own small connector, so actuator traffic never
shares a thread pool with request traffic. It is one property, it changes no invariant, and it was
deliberately not added mid-phase: Phase 5's job was to find this, and changing the thing being
measured while measuring it is how a load test stops meaning anything.

## The application connects to PostgreSQL as a superuser — found in Phase 5

`POSTGRES_USER: ledger` in the compose file makes `ledger` the cluster superuser, because that is
what the `postgres` image does with the user it is told to create. The application then connects as
that role. Verified: `rolsuper`, `rolcreatedb`, `rolcreaterole` and `rolbypassrls` are all true.

This matters beyond the usual least-privilege argument, because CONVENTIONS.md specifies I5's
enforcement as "Trigger that RAISEs **+ DB role grants**", and the second half does not exist. The
role holds UPDATE, DELETE and TRUNCATE on `ledger_entries`, so the trigger is the only thing
standing between the application and mutable history — and a superuser can remove that too. This
was demonstrated and rolled back:

```
BEGIN;
ALTER TABLE ledger_entries DISABLE TRIGGER USER;   -- succeeds
-- 4 triggers now disabled: I1, I5, I7 and I8 all switched off in one statement
ROLLBACK;
```

A bug in the application, not an attacker, is the likely path: any code that reaches `JdbcClient`
with the wrong SQL has the rights to do this.

The fix is two roles rather than one:

- **An owner role** that runs migrations and owns the tables. Flyway needs DDL; nothing else does.
- **An application role** that is not a superuser and holds exactly `SELECT, INSERT` on
  `ledger_entries`, `ledger_transactions` and `outbox_events`, plus `UPDATE` on `accounts.balance`
  for the conditional debit, and nothing on anything else. `REVOKE UPDATE, DELETE, TRUNCATE ON
  ledger_entries` is the line that turns I5's second defense from a sentence in CONVENTIONS.md into a
  fact.

It is deliberately not done inside Phase 5. It changes how every connection in the project
authenticates — the test containers, the load harness and the compose stack all assume one role —
so it needs its own change and its own full test pass rather than being appended to a phase whose
measurements are already recorded.

## The application listens on every interface — found in Phase 5

Spring Boot leaves `server.address` unset, so the ledger binds `0.0.0.0:8080`. It has no
authentication by design (CONVENTIONS.md puts auth out of scope), and it moves money. On the machine
Phase 5 ran on, Windows Firewall carries enabled inbound Allow rules for `java.exe` and
`OpenJDK Platform binary` on the Public profile, and `GET http://<lan-address>:8080/actuator/prometheus`
returned 200 from a non-loopback address. So this is reachable in practice, not only in principle.

`server.address: 127.0.0.1` is the one-line version and it breaks Phase 4: Prometheus scrapes
`host.docker.internal:8080` from inside a container, which arrives on the host's gateway address
rather than on loopback. The two requirements genuinely conflict while the application runs on the
host and its observability runs in Docker.

Moving the application into the compose stack resolves both at once, and is already planned above
for Phase 6: on the compose network Prometheus reaches it by service name, and no host port has to
be published at all. Until then the exposure stands and is worth knowing about.
