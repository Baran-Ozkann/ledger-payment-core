# Architecture decision records

One file per decision that would otherwise be re-argued every time someone new reads the code.
Each has the same four sections, and the fourth is the one worth reading:

- **Context** — what the situation was when the decision had to be made
- **Decision** — what was chosen
- **Consequences** — what that bought and what it costs, measured where a number exists
- **Rejected alternatives** — the specific alternative, and the concrete failure it produces

A record without a rejected alternative is a description of the code, which the code already
provides. The value is in the option that was not taken and the reason it was not taken.

| ADR | Decision |
|---|---|
| [001](001-signed-delta.md) | A signed `amount` on each entry, not separate debit and credit columns |
| [002](002-materialized-balance.md) | A materialized balance reconciled against the entries, not summed on read |
| [003](003-transactional-outbox.md) | A transactional outbox, not CDC and not a dual write |
| [004](004-ordered-locking.md) | READ COMMITTED with ordered locking, not SERIALIZABLE with retries |
| [005](005-idempotency-in-transaction.md) | The idempotency record commits with the ledger write |
| [006](006-hot-account-contention.md) | Hot account contention: measured, sharded counter not implemented |
| [007](007-bigint-minor-units.md) | `BIGINT` minor units, not `NUMERIC` and not `BigDecimal` |
| [008](008-cursor-pagination.md) | Cursor pagination, not `OFFSET` |
| [009](009-validation-rules-are-not-invariants.md) | Why V1, V3 and V7 cannot be written as invariants |
