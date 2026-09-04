package com.baran.ledger.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.baran.ledger.domain.LedgerEntry;

@Repository
public class EntryRepository {

    private static final String SELECT_JOINED = """
            SELECT e.id, t.public_id AS transaction_public_id, a.public_id AS account_public_id,
                   e.amount, e.currency, e.created_at
            FROM ledger_entries e
            JOIN ledger_transactions t ON t.id = e.transaction_id
            JOIN accounts a ON a.id = e.account_id
            """;

    private final JdbcClient jdbc;

    EntryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The currency is the one on the account being posted to, never a constant: a constant would
     * silently disagree with the account the moment two currencies exist, and the entry is the
     * record of what was actually moved.
     */
    public void insert(long transactionId, long accountId, long amount, String currency) {
        jdbc.sql("""
                        INSERT INTO ledger_entries (transaction_id, account_id, amount, currency)
                        VALUES (?, ?, ?, ?)""")
                .params(transactionId, accountId, amount, currency)
                .update();
    }

    public List<LedgerEntry> findByTransaction(long transactionId) {
        return jdbc.sql(SELECT_JOINED + "WHERE e.transaction_id = ? ORDER BY e.id")
                .param(transactionId)
                .query(EntryRepository::mapEntry)
                .list();
    }

    /**
     * Newest first, keyed on the entry id rather than an offset: the index is
     * (account_id, id DESC), so a page costs the same on an account with a million entries.
     */
    public List<LedgerEntry> findByAccount(long accountId, Long after, int limit) {
        return after == null
                ? jdbc.sql(SELECT_JOINED + "WHERE e.account_id = ? ORDER BY e.id DESC LIMIT ?")
                        .params(accountId, limit)
                        .query(EntryRepository::mapEntry)
                        .list()
                : jdbc.sql(SELECT_JOINED + "WHERE e.account_id = ? AND e.id < ? ORDER BY e.id DESC LIMIT ?")
                        .params(accountId, after, limit)
                        .query(EntryRepository::mapEntry)
                        .list();
    }

    private static LedgerEntry mapEntry(ResultSet rs, int rowNum) throws SQLException {
        return new LedgerEntry(
                rs.getLong("id"),
                rs.getObject("transaction_public_id", UUID.class),
                rs.getObject("account_public_id", UUID.class),
                rs.getLong("amount"),
                rs.getString("currency"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
