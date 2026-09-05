package com.baran.ledger.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.baran.ledger.domain.LedgerTransaction;
import com.baran.ledger.domain.TxType;

@Repository
public class TransactionRepository {

    private final JdbcClient jdbc;

    TransactionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(UUID publicId, TxType txType, String description) {
        return jdbc.sql("""
                        INSERT INTO ledger_transactions (public_id, tx_type, description)
                        VALUES (?, ?, ?)
                        RETURNING id""")
                .params(publicId, txType.name(), description)
                .query(Long.class)
                .single();
    }

    /**
     * uq_single_reversal is a unique index on reverses_id, so the second reversal of one
     * transaction fails here. An application-level check could not do this: two requests would
     * both read "not reversed yet" and both write.
     */
    public long insertReversal(UUID publicId, long reversesId, String description) {
        return jdbc.sql("""
                        INSERT INTO ledger_transactions (public_id, tx_type, reverses_id, description)
                        VALUES (?, ?, ?, ?)
                        RETURNING id""")
                .params(publicId, TxType.REVERSAL.name(), reversesId, description)
                .query(Long.class)
                .single();
    }

    public Optional<LedgerTransaction> findByPublicId(UUID publicId) {
        return jdbc.sql("""
                        SELECT id, public_id, tx_type, description, created_at
                        FROM ledger_transactions WHERE public_id = ?""")
                .param(publicId)
                .query(TransactionRepository::mapTransaction)
                .optional();
    }

    private static LedgerTransaction mapTransaction(ResultSet rs, int rowNum) throws SQLException {
        return new LedgerTransaction(
                rs.getLong("id"),
                rs.getObject("public_id", UUID.class),
                TxType.valueOf(rs.getString("tx_type")),
                rs.getString("description"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
