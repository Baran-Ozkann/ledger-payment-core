package com.baran.ledger.schema;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** I4 and the entry amount bounds, all enforced by CHECK constraints and a partial unique index. */
class SchemaConstraintTest extends SchemaTestSupport {

    @Test
    void negativeBalanceRejected() {
        long accountId = insertAccount("ASSET", TRY, 500L, false);

        assertThatThrownBy(() -> debit(accountId, 600L))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("balance_sign");

        assertThat(balanceOf(accountId)).isEqualTo(500L);
    }

    @Test
    void negativeBalanceAllowedOnEquityAccount() {
        long accountId = insertAccount("EQUITY", TRY, 0L, true);

        debit(accountId, 600L);

        assertThat(balanceOf(accountId)).isEqualTo(-600L);
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
