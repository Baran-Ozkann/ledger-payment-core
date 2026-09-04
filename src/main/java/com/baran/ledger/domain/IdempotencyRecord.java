package com.baran.ledger.domain;

/**
 * What a caller needs to know about a key someone else already claimed: whether the request was
 * the same one, whether it finished, and what it answered.
 */
public record IdempotencyRecord(String requestHash, IdempotencyStatus status, String responseBody) {
}
