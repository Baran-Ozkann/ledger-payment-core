package com.baran.ledger.domain;

/** Mirrors the valid_account_type CHECK on accounts. */
public enum AccountType {
    ASSET,
    LIABILITY,
    EQUITY,
    REVENUE,
    EXPENSE
}
