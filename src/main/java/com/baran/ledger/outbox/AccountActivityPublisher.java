package com.baran.ledger.outbox;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.baran.ledger.config.EventTopics;
import com.baran.ledger.domain.OutboxEvent;

import static java.nio.charset.StandardCharsets.UTF_8;

@Component
class AccountActivityPublisher {

    private static final long SEND_TIMEOUT_SECONDS = 10L;

    private final KafkaTemplate<String, String> kafka;

    AccountActivityPublisher(KafkaTemplate<String, String> kafka) {
        this.kafka = kafka;
    }

    /**
     * The send is waited on before the caller is allowed to mark the row published. An
     * asynchronous send would let the relay mark rows the broker never accepted, which is the one
     * direction this must never fail in: an event nobody will ever look for again.
     *
     * <p>The key is the aggregate id, so every event of one account lands on one partition and is
     * read in the order it was written. The payload goes out exactly as it was stored.
     */
    void publish(OutboxEvent event) {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(EventTopics.ACCOUNT_ACTIVITY, event.aggregateId(), event.payload());
        record.headers().add(EventTopics.EVENT_ID_HEADER, Long.toString(event.id()).getBytes(UTF_8));

        try {
            kafka.send(record).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new EventPublishFailed(event, interrupted);
        } catch (ExecutionException | TimeoutException failure) {
            throw new EventPublishFailed(event, failure);
        }
    }
}
