package com.baran.ledger.api;

import java.util.List;

import com.baran.ledger.domain.LedgerEntry;

/**
 * @param nextAfter the cursor for the following page, null once the last page has been reached
 */
record EntriesPage(List<EntryResponse> entries, Long nextAfter) {

    static EntriesPage of(List<LedgerEntry> entries, int limit) {
        Long nextAfter = entries.size() < limit ? null : entries.getLast().id();
        return new EntriesPage(EntryResponse.of(entries), nextAfter);
    }
}
