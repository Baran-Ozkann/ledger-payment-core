package com.baran.ledger.domain;

import java.time.Instant;
import java.util.UUID;

public record LedgerTransaction(
        long id,
        UUID publicId,
        TxType txType,
        String description,
        Instant createdAt) {
}
