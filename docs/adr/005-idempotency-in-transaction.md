# ADR-005 — The idempotency record commits with the ledger write

## Context

I6 says a `(client_id, idempotency_key)` pair maps to at most one transaction, and a `UNIQUE`
constraint is the whole of the mechanism. What is left to decide is the protocol above it: when the
row is written relative to the money.

Two transactions want the same key at the same time. One of them will move money and the other has
to be told what the first one did — including in the case where the first one is still running.

## Decision

One transaction. `LedgerService.idempotently` claims the key, performs the ledger write, and stores
the response body, and all three commit together:

```java
Optional<Long> claim = idempotency.claim(request);   // INSERT ... ON CONFLICT DO NOTHING RETURNING id
if (claim.isEmpty()) {
    return replayOf(request);
}
Completion completion = work.get();
idempotency.complete(claim.get(), CREATED, body, completion.transactionId());
```

A claim that loses the race reads the winner's row and replays its stored status and body. The
`request_hash` — SHA-256 over method, path and canonicalized body — is what separates a retry of
the same request from a second, different request wearing the same key, which is answered
`422 idempotency_key_reuse`.

## Consequences

Because the claim commits with the write, **a row another transaction can see is always a finished
one**. An attempt that fails takes its own claim down with it. There is no in-progress state to
observe, which is why `V9__drop_idempotency_status.sql` removed the `status` column, the
`IN_PROGRESS` value and the `409 request_in_progress` response that phase 2 originally shipped: not
because they were wrong, because they were unreachable.

Three consequences follow, and the first is a cost:

- **Given up: a fast answer to a concurrent duplicate.** A second request carrying a key that is
  currently in flight blocks on the unique index until the first commits or rolls back, and only
  then learns whether it is a replay or the owner. It cannot be answered `409` immediately, because
  answering would require the claim to be visible, which would require it to be committed
  separately. The wait is bounded by the length of a ledger transaction — two row locks and four
  statements, 28.4 ms of service time at 10 VUs.
- **Bought: no key is ever stranded.** See the rejected alternative below; this is the whole
  argument.
- **Also bought: a rejected transfer releases its key.** The rollback takes the claim with it, so a
  client that fixes an invalid request and retries it under the same key is not told the key is
  spent — which is the behaviour a client would expect from a request that moved no money.

One surface detail worth knowing, because it surprises anything parsing the response: a first
execution answers with Jackson's serialization, while a replay answers with the same document read
back out of a `jsonb` column, which PostgreSQL returns with its keys reordered and a space after
each colon. The two are equal as JSON and unequal as bytes.

## Rejected alternatives

**Committing the claim in its own transaction, so a concurrent duplicate can be answered `409`
immediately.**

It strands keys, permanently, and there is no automatic recovery. A crash — or an exception, or a
rollback for insufficient funds — between committing the claim and committing the ledger write
leaves a row that is claimed and will never be completed. That `(client_id, key)` pair can never be
used again: every retry under it finds a committed claim and is answered as a replay of a response
that does not exist, or refused as in-flight forever. The client cannot recover by retrying, which
is the one thing this entire mechanism exists to make safe, and the only fix is an operator
deleting a row from `idempotency_keys` by hand.

The asymmetry is the argument. What the single transaction gives up is a wait bounded by one ledger
transaction. What it avoids is an unbounded failure that needs a human.

**No idempotency table: deduplicate on the request hash against `ledger_transactions`.**

A unique index over something like `(client_id, request_hash)` looks like it removes a table. It
silently swallows legitimate requests. Two genuinely distinct transfers — the same client sending
1 250 kuruş from alice to bob twice, because it happened twice — hash identically, so the second is
rejected as a duplicate. Money that should have moved does not, and the client is handed the first
transfer's response as confirmation that it did. The idempotency key exists precisely so the client
can say which repeats are the same request; deriving it from the content takes that decision away
from the only party that knows the answer.

**Keeping the `status` column and the `IN_PROGRESS` state.**

This is what phase 2 shipped and phase 2 removed. Under this decision the state is unobservable —
no other transaction can ever read a row in it — so the column, the `CHECK` constraint listing its
values, and the `409` branch handling it were dead structure that a reviewer would reasonably read
as a live state machine and design around. `CONVENTIONS.md` forbids a column nothing reads for this
reason: unused structure is worse than absent structure, because it is also a false statement about
how the system behaves.
