package com.baran.ledger.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void additionThatWouldOverflowThrowsInsteadOfWrapping() {
        Money huge = Money.of(Long.MAX_VALUE);

        assertThatThrownBy(() -> huge.plus(Money.of(1L)))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void negationIsExact() {
        assertThat(Money.of(1_250L).negated().minorUnits()).isEqualTo(-1_250L);
    }

    @Test
    void onlyAmountsAboveZeroArePositive() {
        assertThat(Money.of(1L).isPositive()).isTrue();
        assertThat(Money.of(0L).isPositive()).isFalse();
        assertThat(Money.of(-1L).isPositive()).isFalse();
    }

    @Test
    void theMaximumItselfIsAccepted() {
        assertThat(Money.of(Money.MAX_AMOUNT).exceedsMaximum()).isFalse();
        assertThat(Money.of(Money.MAX_AMOUNT + 1).exceedsMaximum()).isTrue();
    }
}
