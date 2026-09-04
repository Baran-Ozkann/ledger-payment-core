package com.baran.ledger.domain;

/**
 * What a caller needs to know about a key someone else already claimed: whether the request was
 * the same one, and what it answered. Both halves of the answer are stored, code and body, so a
 * repeat of a request is told exactly what the first attempt was told.
 */
public record IdempotencyRecord(
        String requestHash, IdempotencyStatus status, int responseCode, String responseBody) {
}
