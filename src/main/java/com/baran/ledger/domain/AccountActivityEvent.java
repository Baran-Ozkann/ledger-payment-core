package com.baran.ledger.domain;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * What one account saw happen: the payload of an outbox event, and the wire format of the topic.
 * One event per entry rather than one per transaction, because the account is the aggregate. That
 * is what lets the aggregate id be the partition key and gives per-account ordering.
 *
 * <p>The contract is additive: components are appended, never renamed or retyped, because a
 * reconciler outside this repository parses the JSON rather than this record. The README's event
 * contract section is the reader-facing copy.
 *
 * @param amount signed, in minor units: negative on the account that was debited
 * @param entryId the ledger_entries row this event describes; null only on an event written before
 *     the field existed, which is why it is boxed: absent must not read as entry zero
 * @param createdAt that row's created_at, not the time the relay published it. Always six
 *     fractional digits in UTC, so the string sorts as the instant does and a pattern can validate
 *     it; the default serializer drops trailing zeros. Null under the same condition as entryId
 */
public record AccountActivityEvent(
        UUID transactionId,
        UUID accountId,
        long amount,
        String currency,
        TxType txType,
        Long entryId,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", timezone = "UTC")
        Instant createdAt) {

    public static final String AGGREGATE_TYPE = "ACCOUNT";
    public static final String EVENT_TYPE = "account.entry_posted";
}
