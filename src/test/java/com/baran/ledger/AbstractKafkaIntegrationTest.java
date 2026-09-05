package com.baran.ledger;

import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.ObjectMapper;

import com.baran.ledger.domain.Account;
import com.baran.ledger.domain.AccountActivityEvent;
import com.baran.ledger.domain.AccountType;
import com.baran.ledger.domain.Money;
import com.baran.ledger.service.LedgerService;

import static org.assertj.core.api.Assertions.fail;

/**
 * A real broker in KRaft mode, tagged so CI can run these apart from the rest: they are the slow
 * part of the suite, and a failure here means something different from a failure anywhere else.
 *
 * <p>The relay and the listener are switched on again here. {@link AbstractIntegrationTest} turns
 * both off, because without a broker they would spend that suite retrying a connection.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "ledger.outbox.relay.enabled=true",
        "spring.kafka.listener.auto-startup=true",
        "spring.kafka.admin.auto-create=true"
})
@Tag("kafka")
@Import(EventProbe.class)
public abstract class AbstractKafkaIntegrationTest extends AbstractIntegrationTest {

    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(50);

    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

    static {
        KAFKA.start();
    }

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected EventProbe probe;

    @Autowired
    protected LedgerService ledger;

    @Autowired
    protected ObjectMapper json;

    /**
     * Test classes that never start a broker leave unpublished rows behind. Clearing them keeps
     * this suite's waits about this suite's events. Published rows are left alone: deleting them
     * is the archival job's business, and it belongs to a later phase.
     */
    @BeforeEach
    void clearTheBacklog() {
        jdbc.sql("DELETE FROM outbox_events WHERE published_at IS NULL").update();
        probe.clear();
    }

    /** Money only enters the ledger through funding, so every balance starts at an EQUITY account. */
    protected Account fundedAccount(long amount) {
        Account equity = ledger.createAccount(AccountType.EQUITY, "ledger-equity");
        Account account = ledger.createAccount(AccountType.LIABILITY, "owner");
        ledger.fund(equity.publicId(), account.publicId(), Money.of(amount), "opening balance");
        return ledger.account(account.publicId());
    }

    protected AccountActivityEvent eventOf(EventProbe.Delivery delivery) {
        return json.readValue(delivery.payload(), AccountActivityEvent.class);
    }

    /** Polls to a deadline rather than sleeping a guessed amount: the relay ticks on its own clock. */
    protected static void await(String description, BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(AWAIT_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep();
        }
        fail("timed out after %s waiting until %s".formatted(AWAIT_TIMEOUT, description));
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL_INTERVAL.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting", interrupted);
        }
    }
}
