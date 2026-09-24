package com.baran.ledger.domain;

import java.time.Instant;

/**
 * What the database assigned an entry as it was inserted. Read back through RETURNING rather than
 * computed here, so the event names the row that was stored and dates it by the column itself,
 * not by an application clock that would disagree with it at the microsecond.
 */
public record EntryReference(long id, Instant createdAt) {
}
