package com.baran.ledger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.kafka.annotation.KafkaListener;

import com.baran.ledger.config.EventTopics;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * A second consumer group with no deduplication of its own, so it records what the broker actually
 * delivered rather than what survived deduplication. That is the only way to tell "the projection
 * ignored the repeat" apart from "the repeat never arrived".
 */
@TestConfiguration
public class EventProbe {

    private static final String GROUP = "phase-3-probe";

    private final List<Delivery> deliveries = Collections.synchronizedList(new ArrayList<>());

    @KafkaListener(topics = EventTopics.ACCOUNT_ACTIVITY, groupId = GROUP)
    void record(ConsumerRecord<String, String> record) {
        Header eventId = record.headers().lastHeader(EventTopics.EVENT_ID_HEADER);
        deliveries.add(new Delivery(
                Long.parseLong(new String(eventId.value(), UTF_8)),
                record.key(),
                record.partition(),
                record.value()));
    }

    /** In arrival order, which is the property the ordering test is about. */
    public List<Delivery> deliveriesFor(Object aggregateId) {
        synchronized (deliveries) {
            return deliveries.stream().filter(delivery -> delivery.aggregateId().equals(aggregateId.toString())).toList();
        }
    }

    public void clear() {
        deliveries.clear();
    }

    public record Delivery(long eventId, String aggregateId, int partition, String payload) {
    }
}
