package com.baran.ledger.outbox;

import com.baran.ledger.domain.OutboxEvent;

/** One event could not be handed to the broker. The row keeps its NULL marker and is retried. */
class EventPublishFailed extends RuntimeException {

    EventPublishFailed(OutboxEvent event, Throwable cause) {
        super("Could not publish event " + event.id(), cause);
    }
}
