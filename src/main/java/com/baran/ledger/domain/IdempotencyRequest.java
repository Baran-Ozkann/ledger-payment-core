package com.baran.ledger.domain;

/**
 * The identity of a mutating request: who sent it, under which key, and a hash of what was asked.
 * The client id is not authenticated - it only scopes keys, so one caller cannot burn another's.
 *
 * @param requestHash SHA-256 over method, path and canonicalized body, so the same key used on a
 *                    different endpoint or with a different body is a reuse rather than a replay
 */
public record IdempotencyRequest(String clientId, String key, String requestHash) {

    public static IdempotencyRequest of(String clientId, String key, String requestHash) {
        if (isBlank(clientId)) {
            throw new LedgerException(LedgerError.MISSING_CLIENT_ID);
        }
        if (isBlank(key)) {
            throw new LedgerException(LedgerError.MISSING_IDEMPOTENCY_KEY);
        }
        return new IdempotencyRequest(clientId, key, requestHash);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
