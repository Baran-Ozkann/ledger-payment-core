package com.baran.ledger.schema;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.baran.ledger.AbstractIntegrationTest;

/**
 * Shared fixtures for the schema tests. These tests are deliberately not @Transactional: a test
 * transaction rolls back, and a deferred constraint trigger only fires on a real commit.
 */
abstract class SchemaTestSupport extends AbstractIntegrationTest {

    static final String TRY = "TRY";

    @Autowired
    JdbcClient jdbc;

    long insertAccount() {
        return insertAccount("ASSET", TRY, 0L, false);
    }

    long insertAccount(String accountType, String currency, long balance, boolean allowNegative) {
        return jdbc.sql("""
                        INSERT INTO accounts (public_id, account_type, currency, balance, allow_negative)
                        VALUES (?, ?, ?, ?, ?)
                        RETURNING id""")
                .params(UUID.randomUUID(), accountType, currency, balance, allowNegative)
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

    void insertEntry(long transactionId, long accountId, long amount, String currency) {
        jdbc.sql("""
                        INSERT INTO ledger_entries (transaction_id, account_id, amount, currency)
                        VALUES (?, ?, ?, ?)""")
                .params(transactionId, accountId, amount, currency)
                .update();
    }
}
