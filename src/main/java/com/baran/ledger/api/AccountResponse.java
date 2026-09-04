package com.baran.ledger.api;

import java.time.Instant;
import java.util.UUID;

import com.baran.ledger.domain.Account;
import com.baran.ledger.domain.AccountType;

record AccountResponse(
        UUID id,
        AccountType accountType,
        String ownerRef,
        String currency,
        long balance,
        boolean allowNegative,
        Instant createdAt) {

    static AccountResponse of(Account account) {
        return new AccountResponse(
                account.publicId(),
                account.accountType(),
                account.ownerRef(),
                account.currency(),
                account.balance(),
                account.allowNegative(),
                account.createdAt());
    }
}
