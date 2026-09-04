package com.baran.ledger.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * @param id doubles as the pagination cursor, which is why it is on the public shape
 */
public record LedgerEntry(
        long id,
        UUID transactionPublicId,
        UUID accountPublicId,
        long amount,
        String currency,
        Instant createdAt) {
}
