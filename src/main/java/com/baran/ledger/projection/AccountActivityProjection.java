package com.baran.ledger.projection;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import com.baran.ledger.config.EventTopics;
import com.baran.ledger.domain.AccountActivityEvent;
import com.baran.ledger.store.AccountActivityRepository;
import com.baran.ledger.store.ConsumedEventRepository;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * A read model of what each account has seen, built from the event stream. It is derived and
 * disposable: no invariant is ever checked against it, because only ledger_entries can answer that.
 */
@Component
public class AccountActivityProjection {

    static final String CONSUMER_GROUP = "account-activity";

    private static final Logger LOG = LoggerFactory.getLogger(AccountActivityProjection.class);

    private final ConsumedEventRepository consumed;
    private final AccountActivityRepository activity;
    private final ObjectMapper json;

    AccountActivityProjection(
            ConsumedEventRepository consumed, AccountActivityRepository activity, ObjectMapper json) {
        this.consumed = consumed;
        this.activity = activity;
        this.json = json;
    }

    /**
     * The deduplication row and the projection write are one transaction, and the offset is
     * committed only after it: the ack mode is RECORD, so the container acks once this method has
     * returned and the transaction has committed. A crash at any point before that replays the
     * record, and the insert refuses it a second time.
     *
     * <p>The check cannot live in the projection statement itself. It accumulates, so nothing in
     * it can tell a repeat from a new entry; delivery is at-least-once, so repeats will come.
     */
    @KafkaListener(topics = EventTopics.ACCOUNT_ACTIVITY, groupId = CONSUMER_GROUP)
    @Transactional
    public void onAccountActivity(ConsumerRecord<String, String> record) {
        long eventId = eventIdOf(record);
        if (!consumed.markConsumed(CONSUMER_GROUP, eventId)) {
            LOG.debug("Event {} was applied by an earlier delivery; skipping", eventId);
            return;
        }

        AccountActivityEvent event = json.readValue(record.value(), AccountActivityEvent.class);
        activity.apply(event.accountId(), event.amount());
    }

    private static long eventIdOf(ConsumerRecord<String, String> record) {
        Header header = record.headers().lastHeader(EventTopics.EVENT_ID_HEADER);
        if (header == null) {
            throw new IllegalStateException("no " + EventTopics.EVENT_ID_HEADER + " header to deduplicate on");
        }
        return Long.parseLong(new String(header.value(), UTF_8));
    }
}
