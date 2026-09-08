# ADR-007 — `BIGINT` minor units, not `NUMERIC` and not `BigDecimal`

## Context

Every amount in this system is a number of Turkish kuruş. The three candidates were binary floating
point (`double`), an exact decimal (`NUMERIC` in PostgreSQL, `BigDecimal` in Java), and an integer
count of the smallest unit.

The choice is load-bearing here rather than a matter of taste, because the invariants are equalities
over sums. I1 is `SUM(amount) = 0`, I2 is the same over the whole table, I3 is
`balance = SUM(amount)`. An equality is only as meaningful as the type it is written over.

## Decision

`BIGINT` in the database, `long` in Java, minor units throughout, wrapped in a `Money` record so
there is one place that says what a number in this ledger means. Arithmetic goes through
`Math.addExact` and `Math.negateExact`. The API rejects a decimal amount with `400`
(`accept-float-as-int: false`), and V2 caps a single transfer at 10 000 000 000 kuruş with a
matching `CHECK` on the entry.

## Consequences

The invariants are exact. `SUM(amount) = 0` is either true or a bug — there is no tolerance to
choose, and choosing one is the thing this decision avoids.

Overflow raises rather than wraps. `Math.addExact` throws `ArithmeticException` where `+` would
silently produce a negative balance out of two positive ones.

The range is not a practical limit: `BIGINT` reaches ±9.22 × 10^18 kuruş, about 92 quadrillion
lira, and the per-entry `CHECK` bounds each entry far below that.

The costs are real and small. Formatting `1250` as `12,50 TRY` is the client's job, and the API
speaks only in kuruş. A body of `1250.00` is a `400` rather than a value that is rounded — because
rounding it would mean the API deciding a rounding rule on the caller's behalf, which is exactly the
decision a payment API should refuse to make silently.

## Rejected alternatives

**`double` or `float`.**

The failure is I2, and it appears on a ledger that is entirely correct. Binary floating point cannot
represent 0.1 exactly, so summing several hundred thousand entries of ordinary amounts does not
return exactly zero — it returns something like −2.3 × 10^−10, and which value depends on the order
PostgreSQL happened to aggregate the rows in. The invariant would then have to be written as
`ABS(SUM(amount)) < epsilon`, and at that moment it stops being able to distinguish accumulated
rounding from a genuinely missing kuruş, which is the only thing it was ever for. `CONVENTIONS.md`
forbids it, and this is the reason.

**`NUMERIC(19,4)` in the database with `BigDecimal` in Java.**

Exact, so the I2 argument above does not apply — and it is still the wrong choice here, for two
reasons.

The first is that `BigDecimal.equals` compares scale as well as value, so `1250.00` and `1250.0000`
are unequal objects that `compareTo` calls equal. Which of the two the driver hands back depends on
the column's declared scale and on what the last operation did to it, so an assertion written with
`isEqualTo` passes or fails on a detail that has nothing to do with the money. Every comparison in
the codebase would have to remember to use `compareTo`, and the one that forgets fails in a way that
looks like a ledger bug.

The second is that a decimal type invites division, and division is where money is created and
destroyed. Dividing a `BigDecimal` by three raises `ArithmeticException` unless a rounding mode is
supplied, so every split becomes a per-call-site decision about who gets the leftover kuruş, made by
whoever wrote that line. With `long` the same operation is integer division plus an explicit
remainder that has to be posted somewhere, which is the accounting question stated in the open
rather than settled by a default.

This system has no fractional amounts at all: a kuruş is the smallest unit it recognises, and
`NUMERIC(19,4)` would carry four decimal places of precision that nothing ever fills.

**`BIGINT` holding major units — lira rather than kuruş.** It cannot represent 12,50 TRY, and
discovering that after the schema is populated means a migration over every historical entry.
Minor units are the smallest unit the currency actually has, which is the only scale that does not
have to be revisited.
