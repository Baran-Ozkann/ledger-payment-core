# ADR-009 — Why V1, V3 and V7 cannot be written as ledger invariants

## Context

This project's premise is that correctness is proven by invariants the database enforces. Eight of
them are listed in `CONVENTIONS.md`, each with a trigger, a constraint or an index behind it, and
each with a test that fails when that mechanism is removed.

It is tempting to conclude that a request which leaves all eight satisfied is a request the ledger
should accept. It is not, and the gap is not a matter of degree.

**An invariant is a statement about the state the ledger is in. A validation rule is a statement
about the request that produced it.** A ledger can be in a perfectly consistent state that was
reached by a request no honest client would ever send, and no predicate over `accounts` and
`ledger_entries` can tell the difference — because the state is genuinely indistinguishable from
one a legitimate request would have produced.

Three of the seven validation rules are in that position, and they are the three most dangerous
ones.

## Decision

V1 (amount strictly positive), V3 (source differs from destination) and V7 (both accounts share a
currency) are enforced at the API, in `LedgerService.post`, before anything is read or written:

```java
if (!amount.isPositive()) {
    throw new LedgerException(LedgerError.INVALID_AMOUNT);
}
if (amount.exceedsMaximum()) {
    throw new LedgerException(LedgerError.AMOUNT_TOO_LARGE);
}
if (fromAccount.equals(toAccount)) {
    throw new LedgerException(LedgerError.SELF_TRANSFER);
}

Account source = account(fromAccount);
Account destination = account(toAccount);
if (!source.currency().equals(destination.currency())) {
    throw new LedgerException(LedgerError.CURRENCY_MISMATCH);
}
```

Each has a test named after it — `negativeAmountRejected`, `selfTransferRejected`,
`crossCurrencyTransferRejected` — and each asserts not only the `422` but that no transaction, no
entry and no balance change was written.

This is not an exception to the rule that forbids application-layer enforcement. That rule is about
invariants, which must have a database-level defense. These are not invariants, which is the point
of this record.

## Consequences

### V1 — a negative amount is a transfer in the opposite direction

`transfer(alice, bob, -100)` runs the ordinary path with the sign inverted. The debit becomes
`balance = balance - (-100)`, which is a credit, and its affordability test becomes
`balance >= -100`, which every non-negative balance satisfies. The credit becomes a debit — but it
is `AccountRepository.credit`, which has no conditional `WHERE`, because on the legitimate path it
never needs one.

The entries written are `+100` on alice and `-100` on bob. All eight invariants hold:

| Invariant | On a negative transfer |
|---|---|
| I1 | +100 − 100 = 0, balanced |
| I2 | the global sum is unchanged |
| I3 | both balances match their entries |
| I4 | bob is still positive, so the `CHECK` never fires |
| I5 | nothing was updated or deleted |
| I6 | one key, one transaction |
| I7 | both entries are TRY on TRY accounts |
| I8 | one currency in the transaction |

The caller has moved money **out of the account they named as the destination**. There is no
authentication in this system, so the only thing standing between any client and any account whose
id they know is the sign of a number.

No invariant catches it because the resulting state is *exactly* the state a legitimate transfer
from bob to alice would have produced. There is no predicate over the tables that separates the two,
because there is nothing to separate: the difference lives entirely in the request.

I4 does refuse it in one case — when the named destination cannot afford the reverse debit — and
that partial coverage is worse than none, because it makes the hole look guarded. Draining an
account that has money in it is precisely the case I4 does not see.

### V3 — a self-transfer is a movement that never happened

`transfer(alice, alice, 100)` debits and credits the same row. The balance ends where it started,
all eight invariants hold, and two entries now record a movement of a hundred kuruş that did not
occur.

No money is created, and this rule is weaker than V1 for that reason. What it costs is the ledger's
integrity as a record, and its capacity as a resource:

- Every audit that reads history rather than balances is wrong. Volume, transfer counts, per-account
  activity and the `account_activity` projection all include movements that never happened, and
  nothing downstream can filter them out, because on the wire they are indistinguishable from real
  transfers between two accounts that happen to be the same one.
- The rows cannot be removed. I5 makes `ledger_entries` append-only, and the application role holds
  no `DELETE` on it (`V12__least_privilege_app_role.sql`). A correction must be a compensating
  entry, which adds two more rows. An unauthenticated caller looping self-transfers writes storage
  this system has deliberately given itself no way to reclaim, plus two outbox events per call into
  a relay measured at 50 events per second.

### V7 — cross-currency, and the invariant that was added because of it

Entries of −1000 TRY on a TRY account and +1000 USD on a USD account sum to zero and each match the
currency of the account they post to. I1, I2, I3 and I7 all pass while a thousand kuruş leave one
account and a thousand cents arrive in another, which at any exchange rate but 1.0 is money created
or destroyed.

V7 is the one of these three that *does* have an invariant behind it, and the history is the
interesting part. The ledger originally had seven invariants; this hole was found during the phase
1b review, and I8 — every entry of a transaction carries one currency — was added as its
database-level defense (`V7__single_currency_transaction_trigger.sql`). "All entries of a
transaction share a currency" is a statement about state, so it can be an invariant. "The amount is
positive" and "the two accounts differ" are not statements about state, so they cannot be.

That leaves V7 doing a different job from V1 and V3, and it is still worth doing. I8 is a deferred
constraint trigger, so it fires at `COMMIT`, after both balances have been written, and it surfaces
as an unhandled exception — a `500`. V7 refuses the same request with `422 currency_mismatch` before
anything is read or written. The invariant is what makes the hole impossible; the validation rule is
what makes the refusal a clean answer.

## Break proof

Per `CONVENTIONS.md`: the mechanism is removed, the tests are run, the failure is recorded, the
mechanism is restored, and the tests are run again.

**Broken.** The three checks quoted above were deleted from `LedgerService.post`. A temporary test
class was added alongside `TransferValidationTest` to record what the eight invariants then allow;
it is not part of the suite and was removed afterwards.

```
[V1] status            = 201 CREATED
[V1] source balance    = 1100 (was 1000)
[V1] dest balance      = 4900 (was 5000)
[V1] I2 sum of entries = 0

[V3] status            = 201 CREATED
[V3] balance           = 1000 (was 1000)
[V3] entries written   = 2
[V3] I2 sum of entries = 0

[V7] status            = 500 INTERNAL_SERVER_ERROR
[V7] body              = {timestamp=2026-09-08T06:21:52.447Z, status=500, error=Internal Server Error, path=/v1/transfers}
[V7] source balance    = 1000 (was 1000)
```

The V1 case is the whole argument in four lines. A request naming alice as the source moved a
hundred kuruş **from bob to alice**, was answered `201 CREATED`, and left the global sum of entries
at zero with no account drifting — that temporary test asserted I2 and I3 explicitly, and both held.

The suite's own tests fail as they should:

```
[ERROR] Tests run: 8, Failures: 4, Errors: 0, Skipped: 0, Time elapsed: 1.089 s <<< FAILURE! -- in com.baran.ledger.api.TransferValidationTest
[ERROR] com.baran.ledger.api.TransferValidationTest.crossCurrencyTransferRejected -- Time elapsed: 0.136 s <<< FAILURE!
org.opentest4j.AssertionFailedError:

expected: 422 UNPROCESSABLE_CONTENT
 but was: 500 INTERNAL_SERVER_ERROR
    at com.baran.ledger.api.TransferValidationTest.crossCurrencyTransferRejected(TransferValidationTest.java:91)

[ERROR] com.baran.ledger.api.TransferValidationTest.negativeAmountRejected -- Time elapsed: 0.137 s <<< FAILURE!
org.opentest4j.AssertionFailedError:

expected: 422 UNPROCESSABLE_CONTENT
 but was: 500 INTERNAL_SERVER_ERROR
    at com.baran.ledger.api.TransferValidationTest.negativeAmountRejected(TransferValidationTest.java:28)

[ERROR] com.baran.ledger.api.TransferValidationTest.zeroAmountRejected -- Time elapsed: 0.127 s <<< FAILURE!
org.opentest4j.AssertionFailedError:

expected: 422 UNPROCESSABLE_CONTENT
 but was: 500 INTERNAL_SERVER_ERROR
    at com.baran.ledger.api.TransferValidationTest.zeroAmountRejected(TransferValidationTest.java:44)

[ERROR] com.baran.ledger.api.TransferValidationTest.selfTransferRejected -- Time elapsed: 0.105 s <<< FAILURE!
org.opentest4j.AssertionFailedError:

expected: 422 UNPROCESSABLE_CONTENT
 but was: 201 CREATED
    at com.baran.ledger.api.TransferValidationTest.selfTransferRejected(TransferValidationTest.java:70)

[ERROR] Tests run: 11, Failures: 4, Errors: 0, Skipped: 0
```

Two of those `500`s are worth reading carefully, because they are invariants doing partial work and
it would be easy to mistake that for coverage. `negativeAmountRejected` and `zeroAmountRejected`
fund their destination with nothing, so the reverse debit drives it below zero and the
`balance_sign` CHECK refuses the statement — I4 catching a negative amount by accident, in the one
case where the attack is pointless. Give the destination money, as the temporary test above does,
and the same request is answered `201`. `crossCurrencyTransferRejected` is I8 firing at commit,
which is the invariant that exists and the `500` that V7 turns into a `422`.

**Restored.** The three checks were put back and nothing else changed.

```
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 15.99 s -- in com.baran.ledger.api.TransferValidationTest
[INFO]
[INFO] Results:
[INFO]
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
[INFO]
[INFO] BUILD SUCCESS
```

## Rejected alternatives

**A `CHECK` constraint requiring a positive amount on `ledger_entries`.** It cannot exist. Every
transaction writes one negative entry and one positive entry — that is what double-entry is — so a
positivity constraint on the column would reject every legitimate transfer. The sign of an entry
carries meaning; the sign of a *request* is a different thing the schema never sees. What the schema
can say, and does, is `CHECK (amount <> 0)` plus a bound on the magnitude, and neither of those
distinguishes a transfer from its inverse.

**A deferred trigger rejecting a transaction whose entries all touch one account, to catch a
self-transfer.** This one is writable — `COUNT(DISTINCT account_id) = 1` over the transaction's
entries — and it would be wrong, because it is not a property of a valid ledger. A legitimate
correction can post two entries to the same account: a fee, a rounding adjustment or an internal
reclassification is exactly that shape. The trigger would forbid transactions that are correct in
order to forbid one that is merely pointless, and it would have to be relaxed the first time such a
correction was needed — at which point it protects nothing and is still in the schema.

**Enforcing V1 inside the transaction, as a trigger over the entries.** This is the version that
sounds like a database-level defense and is not one. A trigger sees `(+100, −100)` on two accounts;
it cannot see that the request said `from: alice`, because the direction is a fact about the request
and is written nowhere in the schema, by design — the ledger records what moved, not who asked for
it. Any trigger it could be given would be comparing the application's writes against the
application's other writes.

**Recording the direction so that such a trigger becomes possible.** A `from_account_id` column on
`ledger_transactions`, populated by the application, letting a trigger assert that the entry for
that account is the negative one. It adds a column duplicating information already in the entries,
whose only reader is a trigger checking the application's claim against the application's writes —
and the same code that got the sign wrong writes both sides, so it would agree with itself. It turns
a three-line guard at the edge of the system into a schema change, a column and a trigger, all of
which trust the layer they exist to defend against.
