package com.baran.ledger.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.baran.ledger.domain.LedgerEntry;

record EntryResponse(
        long id,
        UUID transactionId,
        UUID accountId,
        long amount,
        String currency,
        Instant createdAt) {

    static EntryResponse of(LedgerEntry entry) {
        return new EntryResponse(
                entry.id(),
                entry.transactionPublicId(),
                entry.accountPublicId(),
                entry.amount(),
                entry.currency(),
                entry.createdAt());
    }

    static List<EntryResponse> of(List<LedgerEntry> entries) {
        return entries.stream().map(EntryResponse::of).toList();
    }
}
