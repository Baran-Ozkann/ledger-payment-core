package com.baran.ledger.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.baran.ledger.domain.Account;
import com.baran.ledger.domain.AccountType;
import com.baran.ledger.domain.BalanceDrift;
import com.baran.ledger.domain.Money;

@Repository
public class AccountRepository {

    private static final String COLUMNS =
            "id, public_id, account_type, owner_ref, currency, balance, allow_negative, created_at";

    private final JdbcClient jdbc;

    AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** allow_negative is absent on purpose: it is generated from account_type and rejects a value. */
    public long insert(UUID publicId, AccountType accountType, String ownerRef) {
        return jdbc.sql("""
                        INSERT INTO accounts (public_id, account_type, owner_ref, currency)
                        VALUES (?, ?, ?, ?)
                        RETURNING id""")
                .params(publicId, accountType.name(), ownerRef, Money.CURRENCY)
                .query(Long.class)
                .single();
    }

    public Optional<Account> findByPublicId(UUID publicId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM accounts WHERE public_id = ?")
                .param(publicId)
                .query(AccountRepository::mapAccount)
                .optional();
    }

    /**
     * Takes the row lock and nothing else. Callers lock every account they are about to touch in
     * ascending id order, so a pair of opposing transfers queues instead of deadlocking.
     */
    public void lock(long accountId) {
        jdbc.sql("SELECT id FROM accounts WHERE id = ? FOR UPDATE")
                .param(accountId)
                .query(Long.class)
                .single();
    }

    /**
     * The insufficient-funds test is the WHERE clause, not an if in the caller: the row is only
     * debited if it can afford it, and the affected-row count is how the caller learns the answer.
     */
    public int debit(long accountId, long amount) {
        return jdbc.sql("""
                        UPDATE accounts SET balance = balance - ?
                        WHERE id = ? AND (allow_negative OR balance >= ?)""")
                .params(amount, accountId, amount)
                .update();
    }

    /** No return value, unlike debit: a credit has no condition that could refuse it. */
    public void credit(long accountId, long amount) {
        jdbc.sql("UPDATE accounts SET balance = balance + ? WHERE id = ?")
                .params(amount, accountId)
                .update();
    }

    public long maxId() {
        return jdbc.sql("SELECT COALESCE(max(id), 0) FROM accounts").query(Long.class).single();
    }

    /**
     * I3, for one range of accounts: the materialized balance against the sum of the entries that
     * produced it. The range is what keeps it batched - a single pass over every account would
     * hold read locks and pull the whole entries table through memory on any real dataset.
     *
     * <p>The LEFT JOIN matters. An INNER JOIN would drop an account that has no entries at all,
     * which is exactly the account a stray balance would be sitting on.
     */
    public List<BalanceDrift> findDrift(long lowestId, long highestId) {
        return jdbc.sql("""
                        SELECT a.id, a.balance, COALESCE(SUM(e.amount), 0) AS computed
                        FROM accounts a LEFT JOIN ledger_entries e ON e.account_id = a.id
                        WHERE a.id BETWEEN ? AND ?
                        GROUP BY a.id, a.balance
                        HAVING a.balance <> COALESCE(SUM(e.amount), 0)""")
                .params(lowestId, highestId)
                .query(AccountRepository::mapDrift)
                .list();
    }

    private static BalanceDrift mapDrift(ResultSet rs, int rowNum) throws SQLException {
        return new BalanceDrift(rs.getLong("id"), rs.getLong("balance"), rs.getLong("computed"));
    }

    private static Account mapAccount(ResultSet rs, int rowNum) throws SQLException {
        return new Account(
                rs.getLong("id"),
                rs.getObject("public_id", UUID.class),
                AccountType.valueOf(rs.getString("account_type")),
                rs.getString("owner_ref"),
                rs.getString("currency"),
                rs.getLong("balance"),
                rs.getBoolean("allow_negative"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
