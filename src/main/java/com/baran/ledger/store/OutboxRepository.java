package com.baran.ledger.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.baran.ledger.domain.OutboxEvent;

@Repository
public class OutboxRepository {

    private final JdbcClient jdbc;

    OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Called inside the transfer transaction, so the event and the entries become visible together. */
    public void append(String aggregateType, String aggregateId, String eventType, String payload) {
        jdbc.sql("""
                        INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, payload)
                        VALUES (?, ?, ?, ?::jsonb)""")
                .params(aggregateType, aggregateId, eventType, payload)
                .update();
    }

    /**
     * SKIP LOCKED so a second relay instance would step over a batch another one already holds
     * instead of queueing behind it. Ordering by id keeps a single relay publishing in the order
     * the rows were written; what that costs across instances is the trade written up for ADR-003.
     */
    public List<OutboxEvent> lockUnpublished(int limit) {
        return jdbc.sql("""
                        SELECT id, aggregate_type, aggregate_id, event_type, payload::text AS payload
                        FROM outbox_events
                        WHERE published_at IS NULL
                        ORDER BY id
                        LIMIT ?
                        FOR UPDATE SKIP LOCKED""")
                .param(limit)
                .query(OutboxRepository::mapEvent)
                .list();
    }

    /** Only ever set once: the marker moving from NULL is what takes the row out of the relay's view. */
    public void markPublished(long id) {
        jdbc.sql("UPDATE outbox_events SET published_at = now() WHERE id = ?")
                .param(id)
                .update();
    }

    /** @return how many times this event has now failed, so the caller can say it in the log */
    public int recordFailure(long id, String error) {
        return jdbc.sql("""
                        UPDATE outbox_events SET attempts = attempts + 1, last_error = ?
                        WHERE id = ?
                        RETURNING attempts""")
                .params(error, id)
                .query(Integer.class)
                .single();
    }

    private static OutboxEvent mapEvent(ResultSet rs, int rowNum) throws SQLException {
        return new OutboxEvent(
                rs.getLong("id"),
                rs.getString("aggregate_type"),
                rs.getString("aggregate_id"),
                rs.getString("event_type"),
                rs.getString("payload"));
    }
}
