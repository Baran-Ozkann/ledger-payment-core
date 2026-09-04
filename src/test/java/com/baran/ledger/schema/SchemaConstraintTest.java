package com.baran.ledger.schema;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** I4 and the entry amount bounds, all enforced by CHECK constraints and a partial unique index. */
class SchemaConstraintTest extends SchemaTestSupport {

    @Test
    void negativeBalanceRejected() {
        long accountId = insertAccount("ASSET", TRY, 500L);

        assertThatThrownBy(() -> debit(accountId, 600L))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("balance_sign");

        assertThat(balanceOf(accountId)).isEqualTo(500L);
    }

    @Test
    void negativeBalanceAllowedOnEquityAccount() {
        long accountId = insertAccount("EQUITY", TRY, 0L);

        debit(accountId, 600L);

        assertThat(balanceOf(accountId)).isEqualTo(-600L);
    }

    /**
     * I4 is only as strong as the column it reads. While allow_negative was settable, a LIABILITY
     * account could be created with it set to true and then overdrawn without breaking anything.
     */
    @Test
    void allowNegativeCannotBeSet() {
        assertThatThrownBy(() -> jdbc.sql("""
                                INSERT INTO accounts (public_id, account_type, currency, allow_negative)
                                VALUES (?, 'LIABILITY', ?, TRUE)""")
                .params(UUID.randomUUID(), TRY)
                .update())
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("generated column");
    }

    @Test
    void allowNegativeFollowsAccountType() {
        long liability = insertAccount("LIABILITY", TRY, 0L);
        long equity = insertAccount("EQUITY", TRY, 0L);

        assertThat(allowNegativeOf(liability)).isFalse();
        assertThat(allowNegativeOf(equity)).isTrue();
    }

    @Test
    void zeroAmountRejected() {
        long accountId = insertAccount();
        long transactionId = insertTransaction();

        assertThatThrownBy(() -> insertEntry(transactionId, accountId, 0L, TRY))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("amount_nonzero");
    }

    @Test
    void oversizedAmountRejected() {
        long accountId = insertAccount();
        long transactionId = insertTransaction();

        assertThatThrownBy(() -> insertEntry(transactionId, accountId, 10_000_000_001L, TRY))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("amount_bounded");
    }

    @Test
    void doubleReversalIndexRejected() {
        long originalId = insertTransaction();
        insertReversalOf(originalId);

        assertThatThrownBy(() -> insertReversalOf(originalId))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("uq_single_reversal");
    }

    private void debit(long accountId, long amount) {
        jdbc.sql("UPDATE accounts SET balance = balance - ? WHERE id = ?")
                .params(amount, accountId)
                .update();
    }

    private boolean allowNegativeOf(long accountId) {
        return jdbc.sql("SELECT allow_negative FROM accounts WHERE id = ?")
                .param(accountId)
                .query(Boolean.class)
                .single();
    }

    private long balanceOf(long accountId) {
        return jdbc.sql("SELECT balance FROM accounts WHERE id = ?")
                .param(accountId)
                .query(Long.class)
                .single();
    }

    private void insertReversalOf(long originalId) {
        jdbc.sql("""
                        INSERT INTO ledger_transactions (public_id, tx_type, reverses_id)
                        VALUES (?, 'REVERSAL', ?)""")
                .params(UUID.randomUUID(), originalId)
                .update();
    }
}
