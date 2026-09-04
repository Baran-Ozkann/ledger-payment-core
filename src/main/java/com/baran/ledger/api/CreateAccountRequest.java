package com.baran.ledger.api;

import com.baran.ledger.domain.AccountType;

record CreateAccountRequest(AccountType accountType, String ownerRef, boolean allowNegative) {
}
