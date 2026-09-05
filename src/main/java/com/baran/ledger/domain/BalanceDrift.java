package com.baran.ledger.domain;

/**
 * An account whose materialized balance disagrees with the entries behind it: I3, broken.
 *
 * @param computed what the entries add up to, which is the number that is right by definition
 */
public record BalanceDrift(long accountId, long balance, long computed) {
}
