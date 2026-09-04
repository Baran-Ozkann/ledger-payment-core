package com.baran.ledger.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.baran.ledger.domain.Account;
import com.baran.ledger.domain.AccountType;
import com.baran.ledger.domain.Money;

@Repository
public class AccountRepository {

    private static final String COLUMNS =
            "id, public_id, account_type, owner_ref, currency, balance, allow_negative, created_at";

    private final JdbcClient jdbc;

    AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(UUID publicId, AccountType accountType, String ownerRef, boolean allowNegative) {
        return jdbc.sql("""
                        INSERT INTO accounts (public_id, account_type, owner_ref, currency, allow_negative)
                        VALUES (?, ?, ?, ?, ?)
                        RETURNING id""")
                .params(publicId, accountType.name(), ownerRef, Money.CURRENCY, allowNegative)
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

    public int credit(long accountId, long amount) {
        return jdbc.sql("UPDATE accounts SET balance = balance + ? WHERE id = ?")
                .params(amount, accountId)
                .update();
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
