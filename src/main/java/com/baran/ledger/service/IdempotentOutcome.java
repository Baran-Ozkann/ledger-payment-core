package com.baran.ledger.service;

/**
 * @param replayed whether this response was read back from a key someone already completed, which
 *                 is the difference between 201 and 200 at the edge
 */
public record IdempotentOutcome(boolean replayed, String body) {

    static IdempotentOutcome created(String body) {
        return new IdempotentOutcome(false, body);
    }

    static IdempotentOutcome replayed(String body) {
        return new IdempotentOutcome(true, body);
    }
}
