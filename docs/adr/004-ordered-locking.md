# ADR-004 — READ COMMITTED with ordered locking, not SERIALIZABLE with retries

## Context

A transfer writes two accounts. Two transfers over the same pair in opposite directions are the
textbook deadlock: each takes one row and waits for the other. Something has to decide what happens
when two transactions want the same rows.

There are two coherent answers and this project ran both.

- **Prevent the conflict.** READ COMMITTED, and every account a transaction will touch locked
  `SELECT ... FOR UPDATE` in ascending internal `id` order before anything is written. Two
  transactions asking for the same rows ask in the same sequence, so one waits and neither aborts.
- **Detect the conflict.** SERIALIZABLE, no explicit locks at all, and the transaction the database
  aborts is run again.

The second is not the first plus an isolation level. It is a different design, and it also stops
issuing the two `SELECT ... FOR UPDATE` statements every transfer currently makes — which turns out
to matter more than the isolation level does.

## Decision

READ COMMITTED with ordered locking is the default (`LedgerService.lockInIdOrder`,
`ledger.concurrency.ordered-locking: true`, `max-attempts: 1`).

The alternative is not a paragraph in this file. It is a runnable profile —
`application-serializable.yml`, `ConcurrencyPolicy`, `SerializableConcurrencyTest` — so the
comparison below is measured rather than argued.

## Consequences

Both regimes were measured over identical eleven-minute ramps, 10 to 400 VUs, on the same machine
and the same data (`load/RESULTS.md`). The answer is two sentences, not one.

**Without contention** — scenario U, both ends drawn at random from 10 000 accounts, so two
concurrent transfers share an account about one time in five thousand:

| | READ COMMITTED, ordered locking | SERIALIZABLE, five attempts |
|---|---|---|
| Transfers/s at 50 VUs | 370.9 | **407.1 (+10 %)** |
| Transfers/s at 100 VUs | 371.8 | **409.8 (+10 %)** |
| Service time per connection | 28.4 ms | **~24.6 ms** |
| p99 at 10 VUs | **48.1 ms** | 68.4 ms |
| Requests refused | **0 of 220 773** | 313 of 235 397 (0.13 %) |
| Serialization failures retried | 0 | 6 326 |

SERIALIZABLE was **faster**, by about 10 %, and that is reported as measured rather than explained
away. The cause is not the isolation level; it is the two statements that went away with the
locking. One fewer round trip per transfer against a pool of ten is worth exactly the throughput
observed. Under negligible contention the ordered lock pair is pure overhead: it is paying to
prevent a deadlock that random pairs among ten thousand accounts were never going to have.

**With contention** — scenario H, identical in every respect except that the destination is always
one shared REVENUE account, the way a per-transfer fee would be:

| | READ COMMITTED, ordered locking | SERIALIZABLE, five attempts |
|---|---|---|
| Transfers/s | **68.4** | 51.6 (−25 %) |
| Requests refused | **0 of 41 281** | **45 424 of 76 480 (59.4 %)** |
| Serialization retries | 0 | 219 739 |
| Rollbacks / commits | 0 / 41 281 | 267 641 / 48 783 |
| p99 at 400 VUs | 6 044 ms | 7 006 ms |
| Deadlocks | 0 | 0 |

Three callers in five get a 500, and the survivors are served a quarter more slowly. Eighty-five
percent of transactions started never commit. Five attempts are not enough because the conflict is
not occasional: every transaction wants the same row, so a retry re-enters the contention that
killed it and becomes load on the bottleneck it is queued behind. SERIALIZABLE does not remove the
queue — `Lock:tuple` still dominates the wait events, nine of ten backends parked — it converts the
tail of the queue into aborts.

So the honest summary is both rows of this table:

- **Ordered locking costs about 10 % of throughput** on a workload with no contention, for a
  deadlock that workload was never going to produce.
- **Ordered locking is the difference between every caller being served and three in five being
  refused** on a workload with contention.

A payment ledger is chosen on its worst case rather than its average one, which is why the default
is what it is — but the price is real, it is about a tenth of the easy workload, and a system whose
accounts genuinely never collide would be right to make the other choice.

Two things the locking does *not* buy, worth saying because they are easy to assume:

- It does not remove contention. Scenario H's 68 transfers per second is 5.5× slower than
  uncontended, and ordered locking is why that appears as a queue rather than as deadlocks.
  See [ADR-006](006-hot-account-contention.md).
- It does not weaken I4 when it is switched off. The debit is a conditional `UPDATE` and the
  `balance_sign` CHECK is on the column either way; the serializable profile changes who waits, not
  what is allowed.

## Rejected alternatives

**SERIALIZABLE with a bounded retry loop.** Rejected on the numbers above: under the workload the
design exists for, it refuses 59.4 % of requests where the default refuses none, while running 25 %
slower. Raising the attempt limit does not fix it — the retries are themselves the load on the
contended row, so more attempts make the queue longer, and an unbounded loop is a queue with no
admission control.

**Locking the two accounts in a fixed role order — source first, then destination.** This is the
easy mistake, and it produces the deadlock the ordering exists to prevent. A → B locks A then B
while B → A locks B then A; each holds what the other needs, and PostgreSQL resolves it by killing
one transaction with `deadlock detected` after `deadlock_timeout`. The transfers are perfectly
valid, the failure is intermittent, and it appears only under concurrency, which is the class of
bug a test suite is least likely to reproduce by accident. `bidirectionalNoDeadlock` produces it on
demand: phase 2's break proof replaced ascending-id locking with from-then-to locking and **87 of
100 transfers died on `deadlock detected`**, against zero across five consecutive runs once the
ordering was restored.

**An optimistic `version` column on `accounts`, checked in the `UPDATE`'s `WHERE`.** It solves a
problem this system does not have. The lost-update it protects against is already impossible: the
balance is written by `SET balance = balance - ?` with the affordability test in the same `WHERE`,
which is atomic in the database and reads nothing into the application first. What the column would
add is a second failure mode — a `409` on a stale version — for the same conflict the row lock
already serialises, plus a number in the schema that a reviewer would reasonably read as the
concurrency strategy. `CONVENTIONS.md` forbids it for that reason, and this record is the argument
behind that line.

**No explicit locking at all under READ COMMITTED.** The conditional `UPDATE` keeps each individual
balance correct, so this is subtler than it looks: I4 holds, and I1 through I8 all hold. What breaks
is deadlock freedom, because the two `UPDATE` statements of opposing transfers still take row locks
in the order the statements are issued. The system would work and then intermittently kill
transactions under exactly the load it was built for.
