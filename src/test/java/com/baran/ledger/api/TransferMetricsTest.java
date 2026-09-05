package com.baran.ledger.api;

import java.util.UUID;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/** Every assertion reads the registry the Prometheus endpoint scrapes, not an internal counter. */
class TransferMetricsTest extends ApiTestSupport {

    @Autowired
    MeterRegistry meters;

    @Test
    void successfulTransferIsCountedAndTimed() {
        UUID source = fundedAccount(5_000L);
        UUID destination = createAccount("LIABILITY");
        double before = outcomes("transfer", "success");
        long timed = timerCount("transfer", "success");

        assertThat(post("/v1/transfers", transferBody(source, destination, 1_000L)).status())
                .isEqualTo(HttpStatus.CREATED);

        assertThat(outcomes("transfer", "success") - before).isEqualTo(1.0d);
        assertThat(timerCount("transfer", "success") - timed).isEqualTo(1L);
    }

    @Test
    void rejectionIsCountedUnderItsOwnResult() {
        UUID source = fundedAccount(100L);
        UUID destination = createAccount("LIABILITY");
        double before = outcomes("transfer", "insufficient_funds");

        assertThat(post("/v1/transfers", transferBody(source, destination, 5_000L)).status())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        assertThat(outcomes("transfer", "insufficient_funds") - before).isEqualTo(1.0d);
    }

    @Test
    void replayIsCountedAsAnIdempotencyHit() {
        UUID source = fundedAccount(5_000L);
        UUID destination = createAccount("LIABILITY");
        String key = UUID.randomUUID().toString();
        String body = transferBody(source, destination, 1_000L);
        double before = meters.get("ledger.idempotency.hit").counter().count();

        assertThat(post("/v1/transfers", body, CLIENT_ID, key).status()).isEqualTo(HttpStatus.CREATED);
        assertThat(post("/v1/transfers", body, CLIENT_ID, key).status()).isEqualTo(HttpStatus.CREATED);

        assertThat(meters.get("ledger.idempotency.hit").counter().count() - before)
                .as("the second call did no work; it was answered from the store")
                .isEqualTo(1.0d);
        assertThat(balanceOf(destination)).isEqualTo(1_000L);
    }

    /**
     * The exit criterion for ordered locking. Nothing in this system retries a deadlock, so any
     * count here at all would mean the lock ordering had failed somewhere.
     */
    @Test
    void noDeadlockEverLoses() {
        assertThat(meters.get("ledger.deadlock.retry").counter().count()).isZero();
    }

    @Test
    void outboxBacklogIsVisible() {
        UUID source = fundedAccount(5_000L);
        UUID destination = createAccount("LIABILITY");
        double pending = meters.get("ledger.outbox.pending").gauge().value();

        assertThat(post("/v1/transfers", transferBody(source, destination, 1_000L)).status())
                .isEqualTo(HttpStatus.CREATED);

        // The relay is switched off in these tests, so the two events of the transfer stay pending.
        assertThat(meters.get("ledger.outbox.pending").gauge().value() - pending).isEqualTo(2.0d);
        assertThat(meters.get("ledger.outbox.lag").gauge().value())
                .as("something is waiting, so the oldest row has an age")
                .isGreaterThan(0.0d);
    }

    /** find rather than get: a result nothing has produced yet has no meter, and that reads as zero. */
    /**
     * The names the dashboard queries by. Micrometer renames on the way out - dots become
     * underscores, a counter gains _total, a timer gains its base unit - so the only place the
     * name a query has to use is visible is here, at the endpoint Prometheus reads.
     */
    @Test
    void metricsAreExposedUnderTheNamesPrometheusScrapes() {
        UUID source = fundedAccount(5_000L);
        UUID destination = createAccount("LIABILITY");
        assertThat(post("/v1/transfers", transferBody(source, destination, 1_000L)).status())
                .isEqualTo(HttpStatus.CREATED);

        String scrape = getText("/actuator/prometheus");

        assertThat(scrape).contains(
                "ledger_transfer_total",
                "ledger_transfer_duration_seconds_bucket",
                "ledger_idempotency_hit_total",
                "ledger_deadlock_retry_total",
                "ledger_balance_drift_total",
                "ledger_outbox_pending",
                "ledger_outbox_lag_seconds",
                "ledger_cleanup_rows_deleted_total");
    }

    private double outcomes(String operation, String result) {
        Counter counter = meters.find("ledger.transfer")
                .tag("operation", operation).tag("result", result).counter();
        return counter == null ? 0.0d : counter.count();
    }

    private long timerCount(String operation, String result) {
        Timer timer = meters.find("ledger.transfer.duration")
                .tag("operation", operation).tag("result", result).timer();
        return timer == null ? 0L : timer.count();
    }
}
