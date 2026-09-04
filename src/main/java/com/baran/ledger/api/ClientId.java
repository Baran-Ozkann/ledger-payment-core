package com.baran.ledger.api;

import com.baran.ledger.domain.LedgerError;
import com.baran.ledger.domain.LedgerException;

/**
 * The header is not authenticated and nothing is decided from its value; it exists so that phase 2
 * can scope idempotency keys per caller. Until then it is required and logged, nothing more.
 */
final class ClientId {

    private ClientId() {
    }

    static String require(String value) {
        if (value == null || value.isBlank()) {
            throw new LedgerException(LedgerError.MISSING_CLIENT_ID);
        }
        return value;
    }
}
