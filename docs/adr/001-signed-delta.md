# ADR-001 — A signed `amount` on each entry, not separate debit and credit columns

## Context

An entry records money moving on one account. Double-entry bookkeeping has used two columns for
this since the fifteenth century: a debit column and a credit column, each holding a non-negative
number, exactly one of them filled per line. The other shape is one signed column, negative for a
debit and positive for a credit.

Every invariant this project exists to prove is an arithmetic statement over that column:

- I1 — the entries of a transaction sum to zero
- I2 — every entry in the system sums to zero
- I3 — an account's balance equals the sum of its entries

## Decision

One signed `amount BIGINT NOT NULL`, with `CHECK (amount <> 0)` and a bound mirroring the API's
maximum transfer (`V2__ledger_core.sql`).

## Consequences

Each invariant is one aggregate over one column, and the same expression appears in the trigger,
the reconciliation query and the property test:

```sql
-- I1, in the deferred constraint trigger
SELECT COALESCE(SUM(amount), 0) FROM ledger_entries WHERE transaction_id = NEW.transaction_id;

-- I3, in the reconciliation job
HAVING a.balance <> COALESCE(SUM(e.amount), 0)
```

A reversal is `Math.negateExact` on each posting, so the compensating transaction is written by
the same code path as the original with one sign flipped, and no branch decides which column to
move a number between.

What it costs: a report wanting "total debits this month" writes
`SUM(amount) FILTER (WHERE amount < 0)` rather than `SUM(debit)`. That is one predicate, and this
system has no such report.

## Rejected alternatives

**Separate `debit` and `credit` columns, each non-negative, exactly one populated.**

The failure is that "exactly one populated" is not a thing the schema says for free, and every
invariant silently depends on it. `CHECK (amount <> 0)` becomes three constraints —
`debit >= 0`, `credit >= 0`, and `(debit = 0) <> (credit = 0)` — and if the third is forgotten, a
row with both columns filled is legal, sums correctly on one side, and is a plain accounting lie.

Worse is what `NULL` does to the balance check. With two nullable columns the I1 trigger is
`SUM(debit) <> SUM(credit)`, and `SUM` ignores `NULL`. An entry inserted with a `NULL` debit is
therefore invisible to one half of a comparison that is supposed to prove balance: a two-entry
transaction of `credit 1000` and `debit NULL` passes I1 as written, and the account has been
credited a thousand kuruş against nothing. With one signed column there is no arm of the
comparison to leave out — `NOT NULL` on `amount` is the whole guard.

The third failure is a sign convention that cannot be checked. `SUM(debit - credit)` and
`SUM(credit - debit)` are both plausible readings of an account's balance, they differ only in
sign, and PostgreSQL cannot tell which one was meant. A reconciliation query written with the
convention inverted reports drift on every account, or — on a ledger that happens to be
symmetric — reports none while agreeing with nothing. With a signed delta there is one direction
and the entry itself carries it.
