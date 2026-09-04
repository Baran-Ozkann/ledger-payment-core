package com.baran.ledger.api;

import com.baran.ledger.domain.AccountType;

/** No allow_negative: the column is generated from account_type, so a client cannot ask for it. */
record CreateAccountRequest(AccountType accountType, String ownerRef) {
}
