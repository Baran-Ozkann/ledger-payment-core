package com.baran.ledger.outbox;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.baran.ledger.AbstractKafkaIntegrationTest;
import com.baran.ledger.EventProbe;
import com.baran.ledger.domain.Account;
import com.baran.ledger.domain.AccountActivityEvent;
import com.baran.ledger.domain.AccountType;
import com.baran.ledger.domain.LedgerTransaction;
import com.baran.ledger.domain.Money;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxRelayTest extends AbstractKafkaIntegrationTest {

    @Test
    void relayPublishesAndMarks() {
        Account source = fundedAccount(5_000L);
        Account destination = ledger.createAccount(AccountType.LIABILITY, "owner");

        LedgerTransaction transfer = ledger.transfer(
                source.publicId(), destination.publicId(), Money.of(1_200L), "salary");
        long eventId = eventIdOf(transfer.publicId(), destination.publicId());

        await("both events of the transfer are marked published",
                () -> publishedCountOf(transfer.publicId()) == 2L);
        await("the credited account's event is delivered",
                () -> probe.deliveriesFor(destination.publicId()).size() == 1);

        EventProbe.Delivery delivered = probe.deliveriesFor(destination.publicId()).getFirst();
        assertThat(delivered.eventId()).as("the outbox id travels as the header the consumer dedups on")
                .isEqualTo(eventId);
        assertThat(delivered.aggregateId()).as("the partition key is the account, not the transaction")
                .isEqualTo(destination.publicId().toString());
        assertThat(eventOf(delivered))
                .isEqualTo(new AccountActivityEvent(
                        transfer.publicId(), destination.publicId(), 1_200L, "TRY", transfer.txType()));
    }

    /**
     * The row is given an id that was handed out before the transfer's, and inserted after the
     * transfer's events have already been published. A relay that remembered a high-water mark
     * would have moved past this id and would never publish it; the NULL marker cannot be outrun.
     */
    @Test
    void relayRetriesUnpublished() {
        long skippedId = jdbc.sql("SELECT nextval('outbox_events_id_seq')").query(Long.class).single();
        Account account = fundedAccount(2_000L);
        Account destination = ledger.createAccount(AccountType.LIABILITY, "owner");
        LedgerTransaction transfer = ledger.transfer(
                account.publicId(), destination.publicId(), Money.of(700L), "salary");

        await("the transfer's own events are published and marked",
                () -> publishedCountOf(transfer.publicId()) == 2L);
        insertUnpublished(skippedId, eventIdOf(transfer.publicId(), destination.publicId()));

        await("the older row is published as well", () -> isPublished(skippedId));
        await("and delivered", () -> deliveredIds(destination.publicId()).contains(skippedId));
    }

    private void insertUnpublished(long id, long copyOf) {
        jdbc.sql("""
                        INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload)
                        SELECT ?, aggregate_type, aggregate_id, event_type, payload
                        FROM outbox_events WHERE id = ?""")
                .params(id, copyOf)
                .update();
    }

    private long eventIdOf(UUID transactionPublicId, UUID accountPublicId) {
        return jdbc.sql("""
                        SELECT id FROM outbox_events
                        WHERE payload->>'transaction_id' = ? AND aggregate_id = ?""")
                .params(transactionPublicId.toString(), accountPublicId.toString())
                .query(Long.class)
                .single();
    }

    private long publishedCountOf(UUID transactionPublicId) {
        return jdbc.sql("""
                        SELECT count(*) FROM outbox_events
                        WHERE payload->>'transaction_id' = ? AND published_at IS NOT NULL""")
                .param(transactionPublicId.toString())
                .query(Long.class)
                .single();
    }

    private boolean isPublished(long id) {
        return jdbc.sql("SELECT published_at IS NOT NULL FROM outbox_events WHERE id = ?")
                .param(id)
                .query(Boolean.class)
                .single();
    }

    private List<Long> deliveredIds(UUID accountPublicId) {
        return probe.deliveriesFor(accountPublicId).stream().map(EventProbe.Delivery::eventId).toList();
    }
}
