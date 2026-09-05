package com.baran.ledger.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ConsumedEventRepository {

    private final JdbcClient jdbc;

    ConsumedEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Runs in the transaction that applies the event, so an event counts as consumed exactly when
     * its effect is durable. ON CONFLICT DO NOTHING makes the second delivery a no-op rather than
     * an error: at-least-once delivery is the contract, and a repeat is expected traffic.
     *
     * @return false if this group has already consumed the event, and the caller must not apply it
     */
    public boolean markConsumed(String consumerGroup, long eventId) {
        return jdbc.sql("""
                        INSERT INTO consumed_events (consumer_group, event_id)
                        VALUES (?, ?)
                        ON CONFLICT (consumer_group, event_id) DO NOTHING""")
                .params(consumerGroup, eventId)
                .update() == 1;
    }
}
