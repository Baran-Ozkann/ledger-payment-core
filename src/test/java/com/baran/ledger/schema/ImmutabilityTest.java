package com.baran.ledger.schema;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** I5: ledger records are append-only. Corrections are compensating entries, never edits. */
class ImmutabilityTest extends SchemaTestSupport {

    @Test
    void entryUpdateRejected() {
        long entryId = anyEntryOf(commitBalancedTransaction(1_000L));

        assertThatThrownBy(() -> jdbc.sql("UPDATE ledger_entries SET amount = 1 WHERE id = ?")
                .param(entryId)
                .update())
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("ledger records are immutable: UPDATE on ledger_entries");

        assertThat(entryExists(entryId)).isTrue();
    }

    @Test
    void entryDeleteRejected() {
        long entryId = anyEntryOf(commitBalancedTransaction(1_000L));

        assertThatThrownBy(() -> jdbc.sql("DELETE FROM ledger_entries WHERE id = ?")
                .param(entryId)
                .update())
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("ledger records are immutable: DELETE on ledger_entries");

        assertThat(entryExists(entryId)).isTrue();
    }

    @Test
    void transactionUpdateRejected() {
        long transactionId = commitBalancedTransaction(1_000L);

        assertThatThrownBy(() -> jdbc.sql("UPDATE ledger_transactions SET description = 'edited' WHERE id = ?")
                .param(transactionId)
                .update())
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("ledger records are immutable: UPDATE on ledger_transactions");
    }

    @Test
    void transactionDeleteRejected() {
        long transactionId = commitBalancedTransaction(1_000L);

        assertThatThrownBy(() -> jdbc.sql("DELETE FROM ledger_transactions WHERE id = ?")
                .param(transactionId)
                .update())
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("ledger records are immutable: DELETE on ledger_transactions");

        assertThat(transactionExists(transactionId)).isTrue();
    }

    private long anyEntryOf(long transactionId) {
        return jdbc.sql("SELECT id FROM ledger_entries WHERE transaction_id = ? ORDER BY id LIMIT 1")
                .param(transactionId)
                .query(Long.class)
                .single();
    }

    private boolean entryExists(long entryId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM ledger_entries WHERE id = ?)")
                .param(entryId)
                .query(Boolean.class)
                .single();
    }

    private boolean transactionExists(long transactionId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM ledger_transactions WHERE id = ?)")
                .param(transactionId)
                .query(Boolean.class)
                .single();
    }
}
