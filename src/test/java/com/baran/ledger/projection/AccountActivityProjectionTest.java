package com.baran.ledger.projection;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Test;

import com.baran.ledger.AbstractKafkaIntegrationTest;
import com.baran.ledger.EventProbe;
import com.baran.ledger.domain.Account;
import com.baran.ledger.domain.AccountType;
import com.baran.ledger.domain.LedgerTransaction;
import com.baran.ledger.domain.Money;

import static org.assertj.core.api.Assertions.assertThat;

class AccountActivityProjectionTest extends AbstractKafkaIntegrationTest {

    /**
     * The repeat is produced the way production would produce one: the marker is put back to NULL,
     * which is what a crash between publishing and marking leaves behind. The relay then publishes
     * the same event again, exactly as it would after that crash.
     *
     * <p>The second transfer is the fence. It shares a key with the repeat, so it shares a
     * partition and is consumed after it; seeing the fence applied is proof the repeat was already
     * handled. Waiting on the repeat itself would prove nothing, since doing nothing leaves no
     * trace to wait for.
     */
    @Test
    void consumerReplayIdempotent() {
        Account source = fundedAccount(5_000L);
        Account destination = ledger.createAccount(AccountType.LIABILITY, "owner");
        LedgerTransaction transfer = ledger.transfer(
                source.publicId(), destination.publicId(), Money.of(1_200L), "salary");
        long eventId = eventIdOf(transfer.publicId(), destination.publicId());

        await("the credit is projected", () -> new Activity(1L, 1_200L).equals(activityOf(destination)));

        republish(eventId);
        ledger.transfer(source.publicId(), destination.publicId(), Money.of(300L), "fence");

        await("the event behind the repeat is projected", () -> activityOf(destination).entryCount() >= 2L);
        assertThat(activityOf(destination))
                .as("applied once, however often it was delivered")
                .isEqualTo(new Activity(2L, 1_500L));
        assertThat(deliveryCountOf(destination, eventId)).as("the broker really did deliver it twice").isEqualTo(2L);
        assertThat(consumedCountOf(eventId)).isEqualTo(1L);
    }

    /**
     * Every event of one account carries that account as its key, so they take one partition and
     * are read in the order the relay published them, which is the order they were written in.
     * Ordering across accounts is not claimed and is not preserved: SKIP LOCKED gives that up.
     */
    @Test
    void perAccountOrdering() {
        Account account = fundedAccount(10_000L);
        Account destination = ledger.createAccount(AccountType.LIABILITY, "owner");
        List<Long> amounts = LongStream.rangeClosed(1L, 8L).map(step -> step * 100L).boxed().toList();

        amounts.forEach(amount ->
                ledger.transfer(account.publicId(), destination.publicId(), Money.of(amount), "ordered"));

        await("all nine of the account's events are delivered",
                () -> probe.deliveriesFor(account.publicId()).size() == amounts.size() + 1);

        List<EventProbe.Delivery> deliveries = probe.deliveriesFor(account.publicId());
        assertThat(deliveries).extracting(EventProbe.Delivery::eventId)
                .as("arrival order is the order the events were written in")
                .isSorted();
        assertThat(deliveries).extracting(EventProbe.Delivery::partition)
                .as("one key, one partition; ordering is a per-partition guarantee")
                .containsOnly(deliveries.getFirst().partition());
        assertThat(deliveries.stream().map(delivery -> eventOf(delivery).amount()).toList())
                .containsExactly(10_000L, -100L, -200L, -300L, -400L, -500L, -600L, -700L, -800L);

        await("the projection agrees with what was delivered",
                () -> new Activity(9L, 6_400L).equals(activityOf(account)));
    }

    private void republish(long eventId) {
        jdbc.sql("UPDATE outbox_events SET published_at = NULL WHERE id = ?").param(eventId).update();
    }

    private Activity activityOf(Account account) {
        return jdbc.sql("SELECT entry_count, net_amount FROM account_activity WHERE account_id = ?")
                .param(account.publicId())
                .query(AccountActivityProjectionTest::mapActivity)
                .optional()
                .orElse(new Activity(0L, 0L));
    }

    private long eventIdOf(UUID transactionPublicId, UUID accountPublicId) {
        return jdbc.sql("""
                        SELECT id FROM outbox_events
                        WHERE payload->>'transaction_id' = ? AND aggregate_id = ?""")
                .params(transactionPublicId.toString(), accountPublicId.toString())
                .query(Long.class)
                .single();
    }

    private long consumedCountOf(long eventId) {
        return jdbc.sql("SELECT count(*) FROM consumed_events WHERE event_id = ?")
                .param(eventId)
                .query(Long.class)
                .single();
    }

    private long deliveryCountOf(Account account, long eventId) {
        return probe.deliveriesFor(account.publicId()).stream()
                .filter(delivery -> delivery.eventId() == eventId)
                .count();
    }

    private static Activity mapActivity(ResultSet rs, int rowNum) throws SQLException {
        return new Activity(rs.getLong("entry_count"), rs.getLong("net_amount"));
    }

    private record Activity(long entryCount, long netAmount) {
    }
}
