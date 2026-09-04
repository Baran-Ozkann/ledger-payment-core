package com.baran.ledger.schema;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** I6 at the level that enforces it: the unique constraint, not the protocol built on top of it. */
class IdempotencyKeyTest extends SchemaTestSupport {

    @Test
    void duplicateClientKeyRejected() {
        insertKey("client-a", "key-1");

        assertThatThrownBy(() -> insertKey("client-a", "key-1"))
                .isInstanceOf(DuplicateKeyException.class)
                .hasStackTraceContaining("uq_client_key");
    }

    @Test
    void sameKeyUnderAnotherClientAccepted() {
        insertKey("client-a", "key-2");
        insertKey("client-b", "key-2");

        assertThat(keyCount("key-2")).isEqualTo(2);
    }

    private void insertKey(String clientId, String key) {
        jdbc.sql("""
                        INSERT INTO idempotency_keys (client_id, idem_key, request_hash, expires_at)
                        VALUES (?, ?, 'hash', now() + INTERVAL '24 hours')""")
                .params(clientId, key)
                .update();
    }

    private int keyCount(String key) {
        return jdbc.sql("SELECT count(*) FROM idempotency_keys WHERE idem_key = ?")
                .param(key)
                .query(Integer.class)
                .single();
    }
}
