package com.baran.ledger.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.baran.ledger.domain.LedgerEntry;
import com.baran.ledger.domain.LedgerTransaction;
import com.baran.ledger.domain.TxType;

record TransferResponse(
        UUID id,
        TxType txType,
        String description,
        Instant createdAt,
        List<EntryResponse> entries) {

    static TransferResponse of(LedgerTransaction transaction, List<LedgerEntry> entries) {
        return new TransferResponse(
                transaction.publicId(),
                transaction.txType(),
                transaction.description(),
                transaction.createdAt(),
                EntryResponse.of(entries));
    }
}
