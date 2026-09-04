package com.baran.ledger.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * @param id the internal id, never exposed over the API; it is the ordering key for locking
 */
public record Account(
        long id,
        UUID publicId,
        AccountType accountType,
        String ownerRef,
        String currency,
        long balance,
        boolean allowNegative,
        Instant createdAt) {
}
