package com.baran.ledger.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import com.baran.ledger.store.OutboxRepository;

/**
 * What the relay is behind by. Pending alone cannot answer that: a hundred rows written a second
 * ago is a healthy burst, while one row written an hour ago means delivery has stopped. The age of
 * the oldest unpublished row is the one that pages someone.
 */
@Component
class OutboxMetrics {

    OutboxMetrics(OutboxRepository outbox, MeterRegistry meters) {
        Gauge.builder("ledger.outbox.pending", outbox, OutboxRepository::countPending)
                .description("Outbox rows the relay has not published yet")
                .register(meters);
        Gauge.builder("ledger.outbox.lag", outbox, OutboxRepository::oldestPendingAgeSeconds)
                .description("Age of the oldest unpublished outbox row")
                .baseUnit("seconds")
                .register(meters);
    }
}
