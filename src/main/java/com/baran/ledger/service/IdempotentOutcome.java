package com.baran.ledger.service;

/**
 * @param status the code the request answers with: the one its first execution produced, whether
 *               that execution was this call or an earlier one wearing the same key. Identical
 *               calls get identical answers, so a repeat is not mistaken for a different outcome
 */
public record IdempotentOutcome(int status, String body) {
}
