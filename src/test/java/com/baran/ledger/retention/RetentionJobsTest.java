package com.baran.ledger.retention;

import java.util.UUID;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.baran.ledger.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rows are written with the ages the jobs decide on, because waiting a week for a test is not an
 * option and moving the clock forward would only test the clock.
 */
class RetentionJobsTest extends AbstractIntegrationTest {

    @Autowired
    IdempotencyKeyCleanupJob keyCleanup;

    @Autowired
    OutboxArchivalJob archival;

    @Autowired
    MeterRegistry meters;

    @Autowired
    JdbcClient jdbc;

    @Test
    void expiredIdempotencyKeysRemoved() {
        String client = "retention-" + UUID.randomUUID();
        String expired = insertKey(client, "-1 hour");
        String live = insertKey(client, "23 hours");
        double before = deletedCount("idempotency_keys");

        keyCleanup.removeExpiredKeys();

        assertThat(keyExists(client, expired)).as("past its expiry, so the key is free again").isFalse();
        assertThat(keyExists(client, live)).as("still inside its window").isTrue();
        assertThat(deletedCount("idempotency_keys") - before).isEqualTo(1.0d);
    }

    @Test
    void publishedOutboxEventsArchived() {
        long old = insertEvent("8 days", "8 days");
        long recent = insertEvent("1 day", "1 day");
        double before = deletedCount("outbox_events");

        archival.archivePublished();

        assertThat(eventExists(old)).as("published beyond the seven day window").isFalse();
        assertThat(eventExists(recent)).as("still inside the window a delivery problem is debugged in").isTrue();
        assertThat(deletedCount("outbox_events") - before).isEqualTo(1.0d);
    }

    /**
     * The failure this guards against is filtering on created_at. An event that was never
     * published is the one event that must never be deleted: there would be nothing left to
     * publish it from, and nothing to notice it by.
     */
    @Test
    void unpublishedOutboxEventsNeverArchived() {
        long ancient = insertEvent("90 days", null);

        archival.archivePublished();

        assertThat(eventExists(ancient)).isTrue();
    }

    private String insertKey(String clientId, String expiresIn) {
        String key = UUID.randomUUID().toString();
        jdbc.sql("""
                        INSERT INTO idempotency_keys (client_id, idem_key, request_hash, expires_at)
                        VALUES (?, ?, 'hash', now() + ?::interval)""")
                .params(clientId, key, expiresIn)
                .update();
        return key;
    }

    private boolean keyExists(String clientId, String key) {
        return jdbc.sql("SELECT count(*) FROM idempotency_keys WHERE client_id = ? AND idem_key = ?")
                .params(clientId, key)
                .query(Long.class)
                .single() == 1L;
    }

    /** @param publishedAgo null for an event the relay has never managed to send */
    private long insertEvent(String createdAgo, String publishedAgo) {
        return jdbc.sql("""
                        INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, payload,
                                                   created_at, published_at)
                        VALUES ('ACCOUNT', ?, 'account.entry_posted', '{}'::jsonb,
                                now() - ?::interval, now() - ?::interval)
                        RETURNING id""")
                .params(UUID.randomUUID().toString(), createdAgo, publishedAgo)
                .query(Long.class)
                .single();
    }

    private boolean eventExists(long id) {
        return jdbc.sql("SELECT count(*) FROM outbox_events WHERE id = ?")
                .param(id)
                .query(Long.class)
                .single() == 1L;
    }

    private double deletedCount(String job) {
        return meters.get("ledger.cleanup.rows.deleted").tag("job", job).counter().count();
    }
}
