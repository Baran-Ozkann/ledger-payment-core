# ADR-006 — Hot account contention: measured, sharded counter not implemented

## Context

Every real payment system has an account that everything touches: a fee account, a float account, a
settlement account. Its row is written by every transfer, and rows serialise.

Phase 5 built scenario H to find out what that costs here. It is identical to the uniform scenario
in every respect except one expression — the destination is always the same REVENUE account — so
the difference between the two runs is attributable to the shared row and to nothing else.

## Decision

Measure it, state the ceiling and its cause, and **do not implement sharded balances**. The design
stays one row per account.

## Consequences

The measurement, over identical eleven-minute ramps (`load/RESULTS.md`):

| VUs | Uniform, transfers/s | Hot account, transfers/s | Hot as a fraction |
|---:|---:|---:|---:|
| 10 | 348.5 | 65.7 | 19 % |
| 50 | 370.9 | 67.9 | 18 % |
| 100 | 371.8 | 68.5 | 18 % |
| 200 | 374.2 | 68.6 | 18 % |
| 400 | 374.4 | 68.4 | 18 % |

**One shared row costs a factor of 5.5, at every level of concurrency.** The ratio is constant
because both scenarios saturate at or before the first step and everything after that is queue.

The cause is not the cause of the uniform ceiling, and that difference is the finding:

| | Uniform | Hot account |
|---|---|---|
| `Lock:tuple` wait samples | 0 | 354 – 420 per step |
| Backends waiting on a lock (of 10) | 1 | **9** |
| System CPU, median | **86 %** | **36 %** |
| Deadlocks | 0 | 0 |

The machine is *less* busy under contention than without it. Nine of the ten pool connections are
parked on the REVENUE row at any instant and one is doing work, so the CPU falls to 36 % while
throughput falls by 82 %. That is the cleanest available evidence that this ceiling is not made of
CPU, disk or pool.

The arithmetic closes. Inter-departure time at saturation is 14.6 ms, which is how long one
transaction holds the hot row: `lockInIdOrder` takes both account locks before any write and holds
them to commit. One row held for 14.6 ms is 68 transfers per second, and no number of connections
or cores changes that number.

Two details that are easy to get backwards:

- **Ordered locking still did its job.** Zero deadlocks across 41 281 transfers all fighting over
  one row. Contention and deadlock are different problems and only the second was ever claimed to
  be solved. See [ADR-004](004-ordered-locking.md).
- **14.6 ms is faster than the uniform run's 28.4 ms service time.** A transaction under contention
  mostly has the machine to itself, because the other nine are asleep. The serialised transaction is
  quicker; there is only ever one of it.

The consequence of not fixing it is stated rather than hidden: an account that every transfer
touches caps this system at roughly 68 transfers per second on this hardware, whatever else is
tuned. `README.md` lists it under known limits.

## Rejected alternatives

**A sharded counter: N sub-balance rows per hot account, a random shard debited per transfer, the
balance summed across shards on read.**

It is the standard fix and it breaks two of the eight invariants as they are written.

I4 is `CHECK (allow_negative OR balance >= 0)` on one column. With N shards there is no such column
to constrain, and the natural translation — no shard may go negative — is a *stricter* rule than
the one the system promises. An account holding 1 000 kuruş spread over ten shards would refuse a
500 kuruş debit that landed on a shard holding 100, and the caller would be told they have
insufficient funds while their balance is twice what they asked for. Making that correct needs
borrowing across shards under a lock, which reintroduces the contention, or rebalancing in the
background.

Rebalancing is where I3 goes. Moving 400 kuruş from one shard to another is an `UPDATE` to two
balances with no entries behind it, and `ReconciliationJob` compares `accounts.balance` against
`SUM(ledger_entries.amount)`. A rebalance is indistinguishable from drift — it is drift, by that
definition — so either the job learns to ignore a class of balance change, which is exactly the
blindness it exists not to have, or every rebalance raises a false alarm. The alternative,
compensating entries for a rebalance, writes entries recording money that never moved between
accounts.

And the scale does not warrant it. This is a single-node project with a measured ceiling of 68
transfers per second on a laptop that is 64 % idle while it hits that ceiling. Sharding trades two
invariants for a limit nothing here is close to needing.

**Enlarging the connection pool.** Measured, not assumed: it would not move this number by a single
transfer per second. Nine of ten connections are already asleep on the row; a pool of a hundred
would have ninety-nine asleep. It does move the *uniform* ceiling, which is a pool ceiling —
10 connections × 28.4 ms of held connection — and that difference between the two scenarios is the
reason both were run.

**Holding the hot row for less time — releasing its lock before commit.** The lock is held to commit
because that is what a row lock does in PostgreSQL; releasing early means not taking it, which is
the serializable profile, and [ADR-004](004-ordered-locking.md) measured that: under this exact
workload it refuses 59.4 % of requests and runs 25 % slower. Shortening the transaction itself is
the honest version of this idea, and there is little to shorten — the transaction is two locks, four
statements and two outbox inserts, and the outbox row is what makes the event durable.
