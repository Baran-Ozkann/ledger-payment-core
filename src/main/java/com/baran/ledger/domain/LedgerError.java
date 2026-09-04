package com.baran.ledger.domain;

/** The failures a caller can act on. The HTTP status for each is decided in the API layer. */
public enum LedgerError {

    INVALID_AMOUNT("invalid_amount", "Amount must be strictly positive"),
    AMOUNT_TOO_LARGE("amount_too_large", "Amount exceeds the maximum of " + Money.MAX_AMOUNT),
    SELF_TRANSFER("self_transfer", "Source and destination accounts must differ"),
    INSUFFICIENT_FUNDS("insufficient_funds", "Source account has insufficient funds"),
    INVALID_FUNDING_ACCOUNTS("invalid_funding_accounts", "Funding moves EQUITY to LIABILITY"),
    ACCOUNT_NOT_FOUND("account_not_found", "No such account"),
    TRANSACTION_NOT_FOUND("transaction_not_found", "No such transaction"),
    MISSING_CLIENT_ID("missing_client_id", "The X-Client-Id header is required"),
    INVALID_PAGE_SIZE("invalid_page_size", "Page size is out of range");

    private final String code;
    private final String title;

    LedgerError(String code, String title) {
        this.code = code;
        this.title = title;
    }

    public String code() {
        return code;
    }

    public String title() {
        return title;
    }
}
