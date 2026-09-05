package com.baran.ledger.outbox;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.baran.ledger.AbstractIntegrationTest;
import com.baran.ledger.domain.Account;
import com.baran.ledger.domain.AccountActivityEvent;
import com.baran.ledger.domain.AccountType;
import com.baran.ledger.domain.LedgerError;
import com.baran.ledger.domain.LedgerException;
import com.baran.ledger.domain.LedgerTransaction;
import com.baran.ledger.domain.Money;
import com.baran.ledger.service.LedgerService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The broker plays no part here. What is being tested is that the event is a row written by the
 * transfer transaction, which is decided entirely inside PostgreSQL.
 */
class OutboxWriteTest extends AbstractIntegrationTest {

    @Autowired
    LedgerService ledger;

    @Autowired
    JdbcClient jdbc;

    @Test
    void outboxWrittenAtomically() {
        Account source = fundedAccount(5_000L);
        Account destination = ledger.createAccount(AccountType.LIABILITY, "owner");

        LedgerTransaction transfer = ledger.transfer(
                source.publicId(), destination.publicId(), Money.of(1_200L), "salary");

        List<PendingEvent> events = eventsOf(transfer.publicId());
        assertThat(events)
                .as("one event per entry, unpublished, never attempted")
                .containsExactly(
                        new PendingEvent(source.publicId().toString(), -1_200L, false, 0),
                        new PendingEvent(destination.publicId().toString(), 1_200L, false, 0));
        assertThat(typesOf(transfer.publicId()))
                .containsOnly(AccountActivityEvent.AGGREGATE_TYPE + "/" + AccountActivityEvent.EVENT_TYPE);
    }

    @Test
    void outboxNotWrittenOnRollback() {
        Account source = fundedAccount(100L);
        Account destination = ledger.createAccount(AccountType.LIABILITY, "owner");
        long eventsBefore = eventCountFor(source.publicId());

        assertThatThrownBy(() -> ledger.transfer(source.publicId(), destination.publicId(), Money.of(500L), "too much"))
                .isInstanceOf(LedgerException.class)
                .extracting(failure -> ((LedgerException) failure).error())
                .isEqualTo(LedgerError.INSUFFICIENT_FUNDS);

        assertThat(eventCountFor(source.publicId()))
                .as("the rejected transfer left no event behind")
                .isEqualTo(eventsBefore);
        assertThat(eventCountFor(destination.publicId())).isZero();
        assertThat(ledger.account(source.publicId()).balance()).isEqualTo(100L);
    }

    private List<PendingEvent> eventsOf(UUID transactionPublicId) {
        return jdbc.sql("""
                        SELECT aggregate_id, (payload->>'amount')::bigint AS amount,
                               published_at IS NOT NULL AS published, attempts
                        FROM outbox_events
                        WHERE payload->>'transaction_id' = ?
                        ORDER BY id""")
                .param(transactionPublicId.toString())
                .query(OutboxWriteTest::mapPendingEvent)
                .list();
    }

    private List<String> typesOf(UUID transactionPublicId) {
        return jdbc.sql("""
                        SELECT aggregate_type || '/' || event_type
                        FROM outbox_events WHERE payload->>'transaction_id' = ?""")
                .param(transactionPublicId.toString())
                .query(String.class)
                .list();
    }

    private long eventCountFor(UUID accountPublicId) {
        return jdbc.sql("SELECT count(*) FROM outbox_events WHERE aggregate_id = ?")
                .param(accountPublicId.toString())
                .query(Long.class)
                .single();
    }

    private Account fundedAccount(long amount) {
        Account equity = ledger.createAccount(AccountType.EQUITY, "ledger-equity");
        Account account = ledger.createAccount(AccountType.LIABILITY, "owner");
        ledger.fund(equity.publicId(), account.publicId(), Money.of(amount), "opening balance");
        return ledger.account(account.publicId());
    }

    private static PendingEvent mapPendingEvent(ResultSet rs, int rowNum) throws SQLException {
        return new PendingEvent(
                rs.getString("aggregate_id"),
                rs.getLong("amount"),
                rs.getBoolean("published"),
                rs.getInt("attempts"));
    }

    private record PendingEvent(String aggregateId, long amount, boolean published, int attempts) {
    }
}
