package com.baran.ledger.domain;

/**
 * An amount in minor units (kurus). Every conversion between a raw long and an amount happens
 * through this type, so there is one place to look when asking what a number in the ledger means.
 */
public record Money(long minorUnits) {

    /** 100M TRY. Mirrors the amount_bounded CHECK on ledger_entries. */
    public static final long MAX_AMOUNT = 10_000_000_000L;

    /** The ledger is single-currency; the column exists so entries stay self-describing. */
    public static final String CURRENCY = "TRY";

    public static Money of(long minorUnits) {
        return new Money(minorUnits);
    }

    public Money plus(Money other) {
        return new Money(Math.addExact(minorUnits, other.minorUnits));
    }

    public Money negated() {
        return new Money(Math.negateExact(minorUnits));
    }

    public boolean isPositive() {
        return minorUnits > 0L;
    }

    public boolean exceedsMaximum() {
        return minorUnits > MAX_AMOUNT;
    }
}
