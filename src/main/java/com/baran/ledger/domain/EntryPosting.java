package com.baran.ledger.domain;

import java.util.UUID;

/**
 * One side of a transaction that already exists, as a reversal has to see it: the internal account
 * id, which is the ordering key for locking and the key the balance is updated by, alongside the
 * public id the event carries.
 */
public record EntryPosting(long accountId, UUID accountPublicId, long amount, String currency) {
}
