package com.baran.ledger.domain;

/** Mirrors the valid_status CHECK on idempotency_keys. */
public enum IdempotencyStatus {
    IN_PROGRESS,
    COMPLETED
}
