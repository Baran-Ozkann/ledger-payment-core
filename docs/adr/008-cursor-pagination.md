# ADR-008 — Cursor pagination, not `OFFSET`

## Context

`GET /v1/accounts/{id}/entries` returns an account's history, newest first. The history is
append-only and unbounded: the demo REVENUE account in the phase 5 hot-account scenario collected
41 281 entries in eleven minutes.

## Decision

A keyset cursor on the entry id, with the index that makes it a range scan:

```sql
CREATE INDEX idx_entries_account ON ledger_entries(account_id, id DESC);
```

```sql
-- first page
WHERE e.account_id = ? ORDER BY e.id DESC LIMIT ?
-- subsequent pages
WHERE e.account_id = ? AND e.id < ? ORDER BY e.id DESC LIMIT ?
```

The response carries `next_after`, which is the id of the last row on the page and `null` once the
last page has been reached. `limit` is bounded at 200 (`LedgerService.MAX_PAGE_SIZE`); outside the
range it is `400 invalid_page_size`.

## Consequences

Every page costs the same. The cursor is a position in the index rather than a count of rows to
skip, so page 500 of an account's history is as fast as page 1, and stays that way as the account
accumulates entries.

The client cannot jump to page 7, and there is no total count. Nothing in this API needs either: a
statement is read forwards, and a count over an unbounded append-only table is a full scan that
would be stale before it was rendered.

The cursor is an entry id, which is a `BIGSERIAL` this system already exposes on every entry. It is
opaque only by convention — a client could construct one — and that is acceptable because it selects
nothing the caller cannot already read: the query is scoped to the account in the path either way.

## Rejected alternatives

**`LIMIT ? OFFSET ?`.**

Two failures, and the first is a correctness failure rather than a performance one.

Entries are appended, and the list is newest first, so new rows arrive at the *front* of the
sequence being paged. A client that reads page 1 (`OFFSET 0`), during which two entries are written,
and then reads page 2 (`OFFSET 50`) receives rows 51 and 52 of a list that has shifted down by two —
so the last two rows of page 1 appear again as the first two rows of page 2. A client reconciling
its own statement against this one double-counts them. The bug is invisible on a quiet account and
appears exactly on the busy ones, and it is not a race that can be closed by locking: the two
requests are separate HTTP calls, minutes apart. A cursor is immune because it names a row rather
than a position — `id < 41281` means the same thing however many entries arrived afterwards.

The second failure is cost that grows with use. `OFFSET 100000` makes PostgreSQL walk and discard
100 000 index entries before returning the first row it will use, so the deepest page of the busiest
account is the slowest query in the system, and it gets slower every day that account is active.
Under the phase 5 hot-account workload, where every one of ten connections is already precious, one
such request occupies a connection for the duration of a scan that produces fifty rows.

**A timestamp cursor (`created_at < ?`).** It reads more naturally and it is not unique.
`created_at` defaults to `now()`, which in PostgreSQL is the transaction's start time, so both
entries of one transfer carry the identical value — and under load many transfers do too. Two
entries sharing a timestamp on a page boundary are either both returned or both skipped, depending
on which side of the comparison they land, so the cursor silently repeats or drops rows. The entry
id is unique by construction, which is the property a cursor needs.
