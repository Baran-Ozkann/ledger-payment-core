# ADR-002 — A materialized balance reconciled against the entries, not summed on read

## Context

An account's balance is derivable: it is the sum of the entries posted to it, and `ledger_entries`
is append-only, so that sum is never wrong by construction. Storing the balance a second time in
`accounts.balance` creates a second source of truth for a number that already has one, which is
exactly the kind of duplication a ledger is supposed to avoid.

The reason to do it anyway is I4. An account with `allow_negative = false` must not go negative,
and `CONVENTIONS.md` forbids enforcing that in the application layer: it needs a database-level
defense.

## Decision

`accounts.balance BIGINT NOT NULL DEFAULT 0`, updated inside the same transaction that writes the
entries, with two database-level guards on it:

```sql
CONSTRAINT balance_sign CHECK (allow_negative OR balance >= 0)
```

```sql
UPDATE accounts SET balance = balance - ?
WHERE id = ? AND (allow_negative OR balance >= ?)
```

The affected-row count of that `UPDATE` is how the application learns whether the account could
afford the debit. Zero rows is `insufficient_funds`; there is no `if` reading a balance first.

The duplication is then policed rather than trusted. `ReconciliationJob` runs every five minutes,
recomputes `SUM(amount)` per account in id-range batches of 10 000, compares it to the stored
balance, and — per `CONVENTIONS.md` — raises `ledger_balance_drift_total` and logs the account
ids without correcting anything.

## Consequences

I4 is enforced where it can be enforced atomically. The check and the write are one statement, so
two concurrent debits cannot both observe the same sufficient balance; the second one either sees
the first one's effect or blocks on its row lock.

Reading a balance is a primary-key lookup, and stays one whatever the account's history costs.

The price is I3: a second number that can disagree with the first. That is the entire reason
invariant I3 exists, and the reason the reconciliation job does. It is also why the job never
auto-corrects — drift means one of the two is wrong and nothing in the system knows which, so
overwriting the balance with the recomputed sum would destroy the evidence of how they diverged.

Measured cost: 28.4 ms of held connection per transfer at 10 VUs, of which the two row locks and
four statements are the whole transaction (`load/RESULTS.md`).

## Rejected alternatives

**Summing the entries on every read.** No `balance` column, no I3, no reconciliation job.

It makes I4 impossible to enforce in the database, which is the disqualifying failure. The check
becomes `SELECT SUM(amount) ...` followed by `INSERT`, which is check-then-act across two
statements. Under READ COMMITTED two concurrent transfers out of an account holding 1 000 both
read 1 000, both insert an entry of −1 000, and both commit. The account is now at −1 000 with
`allow_negative = false`, and no constraint was violated — because with no column holding the
balance there is no constraint to violate. There is nothing to attach a `CHECK` to, and nothing
for a conditional `UPDATE` to be conditional on. What is left is an `if` in the service, which is
the thing the rules forbid, and forbid because it is not atomic.

The alternatives that make sum-on-read safe are worse than the column. `SELECT SUM(...) FOR
UPDATE` cannot lock rows that do not exist yet, so it does not prevent the second transfer from
inserting; locking the account row anyway means keeping a row per account to lock, which is the
`accounts` row this decision is about. SERIALIZABLE would detect it, at the cost measured in
ADR-004: under contention it refuses three callers in five.

The second failure is cost, and it grows with success. An account's balance is a full aggregate
over its entries, so the busiest account in the system is also the slowest to read, and it gets
slower every day it is used. The demo REVENUE account in scenario H accumulated 41 281 entries in
eleven minutes.

**Materializing the balance without reconciling it.** Cheaper, and it is how the balance silently
becomes fiction: a bug that writes an entry without its balance update, or a balance update
without its entry, produces a system that answers every query confidently and disagrees with its
own history. Nothing would notice, because the only thing that could notice is the comparison this
alternative removes. I3 exists precisely because I2 is not enough — the ledger as a whole can sum
to zero while individual balances are wrong in offsetting directions.
