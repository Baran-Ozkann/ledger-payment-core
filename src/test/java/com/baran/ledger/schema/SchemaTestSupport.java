package com.baran.ledger.schema;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.baran.ledger.AbstractIntegrationTest;

/**
 * Shared fixtures for the schema tests. These tests are deliberately not @Transactional: a test
 * transaction rolls back, and a deferred constraint trigger only fires on a real commit.
 */
abstract class SchemaTestSupport extends AbstractIntegrationTest {

    static final String TRY = "TRY";

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    /** The unit of commit under test: the deferred balance check runs when this template commits. */
    TransactionTemplate inTransaction;

    @BeforeEach
    void prepareTransactionTemplate() {
        inTransaction = new TransactionTemplate(transactionManager);
    }

    long insertAccount() {
        return insertAccount("ASSET", TRY, 0L);
    }

    long insertAccount(String accountType, String currency, long balance) {
        return jdbc.sql("""
                        INSERT INTO accounts (public_id, account_type, currency, balance)
                        VALUES (?, ?, ?, ?)
                        RETURNING id""")
                .params(UUID.randomUUID(), accountType, currency, balance)
                .query(Long.class)
                .single();
    }

    long insertTransaction() {
        return jdbc.sql("""
                        INSERT INTO ledger_transactions (public_id, tx_type)
                        VALUES (?, 'TRANSFER')
                        RETURNING id""")
                .param(UUID.randomUUID())
                .query(Long.class)
                .single();
    }

    /** Commits a two-entry transfer and returns its id. */
    long commitBalancedTransaction(long amount) {
        long source = insertAccount();
        long destination = insertAccount();
        return inTransaction.execute(status -> {
            long transactionId = insertTransaction();
            insertEntry(transactionId, source, -amount, TRY);
            insertEntry(transactionId, destination, amount, TRY);
            return transactionId;
        });
    }

    void insertEntry(long transactionId, long accountId, long amount, String currency) {
        jdbc.sql("""
                        INSERT INTO ledger_entries (transaction_id, account_id, amount, currency)
                        VALUES (?, ?, ?, ?)""")
                .params(transactionId, accountId, amount, currency)
                .update();
    }
}
