package com.baran.ledger.domain;

/**
 * An unpublished outbox row as the relay reads it.
 *
 * @param aggregateId the partition key on the wire; every event of one account carries the same one
 * @param payload the JSON exactly as it was stored, never re-serialized on the way out
 */
public record OutboxEvent(
        long id,
        String aggregateType,
        String aggregateId,
        String eventType,
        String payload) {
}
