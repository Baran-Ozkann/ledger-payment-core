package com.baran.ledger.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** The wire contract of the account activity topic, shared by the relay and the consumer. */
@Configuration
public class EventTopics {

    public static final String ACCOUNT_ACTIVITY = "ledger.account-activity";

    /**
     * The outbox id, carried beside the payload because it is what the consumer deduplicates on.
     * It is a header rather than a payload field: the payload is stored before the id is known,
     * and re-serializing it on the way out would let the wire disagree with the row.
     */
    public static final String EVENT_ID_HEADER = "event-id";

    /**
     * More than one partition, or the ordering the partition key buys would be an accident of
     * there being nowhere else for an event to go. Ordering is per key, never across the topic.
     */
    private static final int PARTITIONS = 3;

    private static final int REPLICAS = 1;

    @Bean
    NewTopic accountActivityTopic() {
        return TopicBuilder.name(ACCOUNT_ACTIVITY).partitions(PARTITIONS).replicas(REPLICAS).build();
    }
}
