package com.baran.ledger.domain;

import java.util.UUID;

/**
 * What one account saw happen: the payload of an outbox event, and the wire format of the topic.
 * One event per entry rather than one per transaction, because the account is the aggregate. That
 * is what lets the aggregate id be the partition key and gives per-account ordering.
 *
 * @param amount signed, in minor units: negative on the account that was debited
 */
public record AccountActivityEvent(
        UUID transactionId,
        UUID accountId,
        long amount,
        String currency,
        TxType txType) {

    public static final String AGGREGATE_TYPE = "ACCOUNT";
    public static final String EVENT_TYPE = "account.entry_posted";
}
