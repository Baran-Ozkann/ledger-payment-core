package com.baran.ledger.outbox;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.baran.ledger.domain.OutboxEvent;
import com.baran.ledger.store.OutboxRepository;

/**
 * Publishes what the transfer transaction wrote, from a transaction of its own. It has to be a
 * separate one: a broker call inside the transfer would announce work that can still roll back,
 * and would fail transfers that are otherwise perfectly good.
 *
 * <p>Delivery is therefore at-least-once, deliberately. A crash between the send and the marking
 * republishes the event on the next tick, and the consumer's deduplication absorbs it. The
 * alternative, marking first, trades a duplicate nobody notices for a loss nobody can detect.
 */
@Component
@ConditionalOnProperty(name = "ledger.outbox.relay.enabled", matchIfMissing = true)
public class OutboxRelay {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxRelay.class);

    /** One batch is one transaction, and the rows in it stay locked for its duration. */
    private static final int BATCH_SIZE = 100;

    private final OutboxRepository outbox;
    private final AccountActivityPublisher publisher;

    OutboxRelay(OutboxRepository outbox, AccountActivityPublisher publisher) {
        this.outbox = outbox;
        this.publisher = publisher;
    }

    /**
     * Events go out one at a time, in id order, each waited on and marked before the next is sent.
     * A failure stops the batch rather than skipping past it, because publishing the rest would
     * reorder the events of any account the failed one belongs to. What has already been marked
     * still commits: the batch keeps its progress, and the rest is picked up on the next tick.
     */
    @Scheduled(fixedDelayString = "${ledger.outbox.relay.interval-ms}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> pending = outbox.lockUnpublished(BATCH_SIZE);

        for (OutboxEvent event : pending) {
            try {
                publisher.publish(event);
            } catch (EventPublishFailed failure) {
                int attempts = outbox.recordFailure(event.id(), String.valueOf(failure.getCause()));
                LOG.warn("Publishing {} event {} failed {} time(s); the batch stops here",
                        event.eventType(), event.id(), attempts, failure);
                return;
            }
            outbox.markPublished(event.id());
        }
    }
}
