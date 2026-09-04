package com.baran.ledger.schema;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionSystemException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** I1: the entries of a transaction sum to zero, checked when the transaction commits. */
class BalancedTransactionTest extends SchemaTestSupport {

    @Test
    void unbalancedTransactionRejected() {
        long source = insertAccount();
        long destination = insertAccount();

        // Each insert on its own is legal; only the commit can see that the pair does not net to zero.
        assertThatThrownBy(() -> inTransaction.executeWithoutResult(status -> {
            long transactionId = insertTransaction();
            insertEntry(transactionId, source, -1_000L, TRY);
            insertEntry(transactionId, destination, 900L, TRY);
        }))
                // Failing on commit rather than on an insert is what proves the check is deferred.
                .isInstanceOf(TransactionSystemException.class)
                .hasMessageContaining("commit")
                .hasStackTraceContaining("unbalanced transaction")
                .hasStackTraceContaining("sum=-100");

        assertThat(entryCountForAccount(source)).isZero();
        assertThat(entryCountForAccount(destination)).isZero();
    }

    @Test
    void balancedTransactionAccepted() {
        long transactionId = commitBalancedTransaction(1_000L);

        assertThat(entryCountOf(transactionId)).isEqualTo(2);
        assertThat(entrySumOf(transactionId)).isZero();
    }

    private int entryCountForAccount(long accountId) {
        return jdbc.sql("SELECT count(*) FROM ledger_entries WHERE account_id = ?")
                .param(accountId)
                .query(Integer.class)
                .single();
    }

    private int entryCountOf(long transactionId) {
        return jdbc.sql("SELECT count(*) FROM ledger_entries WHERE transaction_id = ?")
                .param(transactionId)
                .query(Integer.class)
                .single();
    }

    private long entrySumOf(long transactionId) {
        return jdbc.sql("SELECT COALESCE(SUM(amount), 0) FROM ledger_entries WHERE transaction_id = ?")
                .param(transactionId)
                .query(Long.class)
                .single();
    }
}
