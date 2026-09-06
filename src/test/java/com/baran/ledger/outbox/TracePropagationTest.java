package com.baran.ledger.outbox;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.baran.ledger.AbstractKafkaIntegrationTest;
import com.baran.ledger.EventProbe;
import com.baran.ledger.domain.Account;
import com.baran.ledger.domain.AccountType;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The trace is started by the caller rather than by the server, so the test knows the trace id it
 * is looking for before the request is made. Everything downstream then has to agree with a value
 * that was decided outside the application, which is what a real caller's trace would be.
 */
class TracePropagationTest extends AbstractKafkaIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
            new ParameterizedTypeReference<>() {
            };

    @LocalServerPort
    int port;

    private RestTestClient http;

    @BeforeEach
    void bindToRunningServer() {
        http = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    /**
     * The exit criterion the screenshot shows, asserted rather than looked at. Four hops separate
     * the two ends - request thread, outbox row, relay thread, broker - and each one is somewhere
     * the context can be dropped without any test noticing, because the money still moves.
     */
    @Test
    void oneTraceSpansTheRequestAndTheConsumer() {
        Account source = fundedAccount(5_000L);
        Account destination = ledger.createAccount(AccountType.LIABILITY, "owner");
        String traceId = randomHex(32);

        assertThat(transfer(source, destination, 1_200L, traceparent(traceId, randomHex(16))))
                .isEqualTo(HttpStatus.CREATED);

        await("the credited account's event is delivered",
                () -> !probe.deliveriesFor(destination.publicId()).isEmpty());

        assertThat(storedTraceParents(destination.publicId()))
                .as("the request wrote its own trace onto the row, inside the transfer transaction")
                .isNotEmpty()
                .allSatisfy(stored -> assertThat(stored).contains(traceId));

        EventProbe.Delivery delivered = probe.deliveriesFor(destination.publicId()).getFirst();
        assertThat(delivered.traceId())
                .as("and the consumer is running in that same trace, minutes and two threads later")
                .isEqualTo(traceId);
    }

    /**
     * An event written outside a trace has no context to carry, and every row written before V11
     * looks the same way. The relay must still publish it: losing an event because nobody was
     * tracing would be the observability tail wagging the dog.
     */
    @Test
    void eventWithoutATraceIsStillPublished() {
        Account account = ledger.createAccount(AccountType.LIABILITY, "owner");
        long eventId = insertUntracedEvent(account.publicId());

        await("it is published like any other row", () -> isPublished(eventId));
        await("and delivered", () -> probe.deliveriesFor(account.publicId()).stream()
                .anyMatch(delivery -> delivery.eventId() == eventId));
    }

    /** Written straight to the table, because a transfer can no longer produce a row without one. */
    private long insertUntracedEvent(UUID accountPublicId) {
        return jdbc.sql("""
                        INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, payload)
                        VALUES ('account', ?, 'account.activity', ?::jsonb)
                        RETURNING id""")
                .params(accountPublicId.toString(), """
                        {"transaction_id": "%s", "account_id": "%s", "amount": 1,
                         "currency": "TRY", "tx_type": "TRANSFER"}"""
                        .formatted(UUID.randomUUID(), accountPublicId))
                .query(Long.class)
                .single();
    }

    private boolean isPublished(long eventId) {
        return jdbc.sql("SELECT published_at IS NOT NULL FROM outbox_events WHERE id = ?")
                .param(eventId)
                .query(Boolean.class)
                .single();
    }

    private List<String> storedTraceParents(UUID accountPublicId) {
        return jdbc.sql("SELECT trace_parent FROM outbox_events WHERE aggregate_id = ?")
                .param(accountPublicId.toString())
                .query(String.class)
                .list();
    }

    private HttpStatusCode transfer(Account from, Account to, long amount, String traceparent) {
        return http.post()
                .uri("/v1/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Client-Id", "phase-4-tracing")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("traceparent", traceparent)
                .body("""
                        {"from_account": "%s", "to_account": "%s", "amount": %d, "description": "traced"}"""
                        .formatted(from.publicId(), to.publicId(), amount))
                .exchange()
                .returnResult(JSON_OBJECT)
                .getStatus();
    }

    /** Version 00, and the sampled flag set: an unsampled parent would be dropped, not propagated. */
    private static String traceparent(String traceId, String spanId) {
        return "00-" + traceId + "-" + spanId + "-01";
    }

    private static String randomHex(int length) {
        String hex = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        return hex.substring(0, length);
    }
}
