package com.baran.ledger.schema;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionSystemException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** I8: the entries of a transaction share one currency, checked when the transaction commits. */
class TransactionCurrencyTest extends SchemaTestSupport {

    @Test
    void mixedCurrencyTransactionRejected() {
        long tryAccount = insertAccount("ASSET", TRY, 0L);
        long usdAccount = insertAccount("ASSET", USD, 0L);

        // The pair sums to zero and each entry matches its own account, so I1 and I7 both pass.
        // The message is asserted for that reason: only I8 can be what rejected this.
        assertThatThrownBy(() -> inTransaction.executeWithoutResult(status -> {
            long transactionId = insertTransaction();
            insertEntry(transactionId, tryAccount, -1_000L, TRY);
            insertEntry(transactionId, usdAccount, 1_000L, USD);
        }))
                .isInstanceOf(TransactionSystemException.class)
                .hasMessageContaining("commit")
                .hasStackTraceContaining("mixed currencies in transaction")
                .hasStackTraceContaining("2 distinct");

        assertThat(entryCountForAccount(tryAccount)).isZero();
        assertThat(entryCountForAccount(usdAccount)).isZero();
    }

    @Test
    void singleCurrencyTransactionAccepted() {
        long transactionId = commitBalancedTransaction(1_000L);

        assertThat(currencyCountOf(transactionId)).isEqualTo(1);
    }

    private int currencyCountOf(long transactionId) {
        return jdbc.sql("SELECT COUNT(DISTINCT currency) FROM ledger_entries WHERE transaction_id = ?")
                .param(transactionId)
                .query(Integer.class)
                .single();
    }

    private int entryCountForAccount(long accountId) {
        return jdbc.sql("SELECT count(*) FROM ledger_entries WHERE account_id = ?")
                .param(accountId)
                .query(Integer.class)
                .single();
    }
}
