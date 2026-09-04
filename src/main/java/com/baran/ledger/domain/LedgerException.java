package com.baran.ledger.domain;

/** Unchecked so that a rejection inside a transaction rolls back everything written before it. */
public class LedgerException extends RuntimeException {

    private final LedgerError error;

    public LedgerException(LedgerError error) {
        super(error.code());
        this.error = error;
    }

    public LedgerError error() {
        return error;
    }
}
