package com.baran.ledger.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.baran.ledger.domain.IdempotencyRecord;
import com.baran.ledger.domain.IdempotencyRequest;
import com.baran.ledger.domain.IdempotencyStatus;

@Repository
public class IdempotencyRepository {

    private final JdbcClient jdbc;

    IdempotencyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * ON CONFLICT DO NOTHING returns an empty result instead of raising, so an empty Optional means
     * "another request owns this key", not an error. A concurrent claimer blocks here until the
     * first one commits or rolls back, which is what makes the winner unambiguous.
     *
     * <p>The expiry is stamped by the database clock that stamps created_at. Nothing reads it yet;
     * the sweeper that will is recorded in docs/future.md.
     */
    public Optional<Long> claim(IdempotencyRequest request) {
        return jdbc.sql("""
                        INSERT INTO idempotency_keys (client_id, idem_key, request_hash, status, expires_at)
                        VALUES (?, ?, ?, 'IN_PROGRESS', now() + INTERVAL '24 hours')
                        ON CONFLICT (client_id, idem_key) DO NOTHING
                        RETURNING id""")
                .params(request.clientId(), request.key(), request.requestHash())
                .query(Long.class)
                .optional();
    }

    public Optional<IdempotencyRecord> find(String clientId, String key) {
        return jdbc.sql("""
                        SELECT request_hash, status, response_body
                        FROM idempotency_keys
                        WHERE client_id = ? AND idem_key = ?""")
                .params(clientId, key)
                .query(IdempotencyRepository::mapRecord)
                .optional();
    }

    /** Runs in the transaction that did the ledger write, so the two become visible together. */
    public void complete(long id, int responseCode, String responseBody, Long transactionId) {
        jdbc.sql("""
                        UPDATE idempotency_keys
                        SET status = 'COMPLETED', response_code = ?, response_body = ?::jsonb,
                            transaction_id = ?::bigint
                        WHERE id = ?""")
                .params(responseCode, responseBody, transactionId, id)
                .update();
    }

    private static IdempotencyRecord mapRecord(ResultSet rs, int rowNum) throws SQLException {
        return new IdempotencyRecord(
                rs.getString("request_hash"),
                IdempotencyStatus.valueOf(rs.getString("status")),
                rs.getString("response_body"));
    }
}
