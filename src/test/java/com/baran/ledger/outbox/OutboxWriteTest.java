package com.baran.ledger.outbox;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.baran.ledger.AbstractIntegrationTest;
import com.baran.ledger.domain.Account;
import com.baran.ledger.domain.AccountActivityEvent;
import com.baran.ledger.domain.AccountType;
import com.baran.ledger.domain.LedgerError;
import com.baran.ledger.domain.LedgerException;
import com.baran.ledger.domain.LedgerTransaction;
import com.baran.ledger.domain.Money;
import com.baran.ledger.domain.TxType;
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

    @Autowired
    ObjectMapper json;

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

    /**
     * Both sides are read out of PostgreSQL, and the ledger side is formatted by PostgreSQL itself:
     * comparing text against text is what shows the microseconds survived, where comparing two
     * parsed instants would forgive a payload that had lost them on the way.
     */
    @Test
    void eventNamesTheEntryItDescribes() {
        Account source = fundedAccount(5_000L);
        Account destination = ledger.createAccount(AccountType.LIABILITY, "owner");

        LedgerTransaction transfer = ledger.transfer(
                source.publicId(), destination.publicId(), Money.of(1_200L), "salary");

        assertThat(referencesInEvents(transfer.publicId()))
                .as("each account's event carries that account's own entry, dated as it was stored")
                .hasSize(2)
                .isEqualTo(referencesInLedger(transfer.publicId()));
    }

    /**
     * Fixed instants, because the ones a transfer produces end in a zero only now and then: the
     * default serializer's trimming would slip past a test that waited for the clock to supply one.
     * Serialized by the application's own mapper, which is the one announce writes the row with.
     */
    @Test
    void createdAtAlwaysCarriesSixFractionalDigits() {
        assertThat(serializedCreatedAt(Instant.parse("2026-09-24T00:00:00Z")))
                .isEqualTo("2026-09-24T00:00:00.000000Z");
        assertThat(serializedCreatedAt(Instant.parse("2026-09-23T23:59:59.120000Z")))
                .isEqualTo("2026-09-23T23:59:59.120000Z");
        assertThat(serializedCreatedAt(Instant.parse("2026-09-23T23:59:59.999999Z")))
                .isEqualTo("2026-09-23T23:59:59.999999Z");
    }

    /** A reversal writes entries of its own; pointing at the original's would reconcile them twice. */
    @Test
    void reversalEventsNameTheReversalsEntries() {
        Account source = fundedAccount(5_000L);
        Account destination = ledger.createAccount(AccountType.LIABILITY, "owner");
        LedgerTransaction transfer = ledger.transfer(
                source.publicId(), destination.publicId(), Money.of(1_200L), "salary");

        LedgerTransaction reversal = ledger.reverse(transfer.publicId());

        assertThat(referencesInEvents(reversal.publicId()))
                .hasSize(2)
                .isEqualTo(referencesInLedger(reversal.publicId()));
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

    private String serializedCreatedAt(Instant createdAt) {
        AccountActivityEvent event = new AccountActivityEvent(
                UUID.randomUUID(), UUID.randomUUID(), 1L, "TRY", TxType.TRANSFER, 1L, createdAt);
        JsonNode payload = json.readTree(json.writeValueAsString(event));
        return payload.get("created_at").stringValue();
    }

    private List<EntryReferenceText> referencesInEvents(UUID transactionPublicId) {
        return jdbc.sql("""
                        SELECT aggregate_id AS account_id, payload->>'entry_id' AS entry_id,
                               payload->>'created_at' AS created_at
                        FROM outbox_events
                        WHERE payload->>'transaction_id' = ?
                        ORDER BY aggregate_id""")
                .param(transactionPublicId.toString())
                .query(OutboxWriteTest::mapReference)
                .list();
    }

    private List<EntryReferenceText> referencesInLedger(UUID transactionPublicId) {
        return jdbc.sql("""
                        SELECT a.public_id::text AS account_id, e.id::text AS entry_id,
                               to_char(e.created_at AT TIME ZONE 'UTC',
                                       'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS created_at
                        FROM ledger_entries e
                        JOIN ledger_transactions t ON t.id = e.transaction_id
                        JOIN accounts a ON a.id = e.account_id
                        WHERE t.public_id = ?
                        ORDER BY a.public_id::text""")
                .param(transactionPublicId)
                .query(OutboxWriteTest::mapReference)
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

    private static EntryReferenceText mapReference(ResultSet rs, int rowNum) throws SQLException {
        return new EntryReferenceText(rs.getString("account_id"), rs.getString("entry_id"), rs.getString("created_at"));
    }

    private record EntryReferenceText(String accountId, String entryId, String createdAt) {
    }

    private record PendingEvent(String aggregateId, long amount, boolean published, int attempts) {
    }
}
