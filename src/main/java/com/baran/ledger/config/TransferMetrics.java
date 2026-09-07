package com.baran.ledger.config;

import java.util.Locale;
import java.util.function.Supplier;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.stereotype.Component;

import com.baran.ledger.domain.LedgerError;
import com.baran.ledger.domain.LedgerException;
import com.baran.ledger.domain.TxType;

/**
 * Wraps a ledger operation from outside its transaction. That is the whole reason this is called
 * from the controller and not from inside the service: the deferred constraint triggers fire at
 * commit, so a wrapper inside the transaction would report a success that the database then
 * refused, and would never see the failure at all.
 */
@Component
public class TransferMetrics {

    private static final String DURATION = "ledger.transfer.duration";
    private static final String OUTCOMES = "ledger.transfer";

    private final MeterRegistry meters;
    private final ConcurrencyPolicy concurrency;
    private final Counter idempotencyHits;
    private final Counter deadlocks;

    TransferMetrics(MeterRegistry meters, ConcurrencyPolicy concurrency) {
        this.meters = meters;
        this.concurrency = concurrency;
        this.idempotencyHits = Counter.builder("ledger.idempotency.hit")
                .description("Requests answered from a stored response instead of being executed")
                .register(meters);
        this.deadlocks = Counter.builder("ledger.deadlock.retry")
                .description("Operations PostgreSQL killed as the loser of a deadlock")
                .register(meters);
    }

    /**
     * The timer is started outside the retry policy rather than inside it: an attempt the database
     * threw away is still time the caller spent waiting, and a measurement that counts only the
     * winning attempt would describe a system nobody is talking to.
     */
    public <T> T record(TxType operation, Supplier<T> work) {
        Timer.Sample sample = Timer.start(meters);
        try {
            T outcome = concurrency.attempt(work);
            stop(sample, operation, "success");
            return outcome;
        } catch (LedgerException rejected) {
            stop(sample, operation, resultOf(rejected.error()));
            throw rejected;
        } catch (DeadlockLoserDataAccessException deadlock) {
            // Ordered locking is meant to make this unreachable. The counter is how that claim is
            // checked in production rather than only in the concurrency tests; nothing retries.
            deadlocks.increment();
            stop(sample, operation, "error");
            throw deadlock;
        } catch (RuntimeException failure) {
            stop(sample, operation, "error");
            throw failure;
        }
    }

    /** A repeat that was answered from the store: work not done, which is the point of the key. */
    public void idempotencyHit() {
        idempotencyHits.increment();
    }

    private void stop(Timer.Sample sample, TxType operation, String result) {
        String name = operation.name().toLowerCase(Locale.ROOT);
        sample.stop(Timer.builder(DURATION)
                .tag("operation", name)
                .tag("result", result)
                .description("How long a ledger operation took, from the controller in")
                .publishPercentileHistogram()
                .register(meters));
        meters.counter(OUTCOMES, "operation", name, "result", result).increment();
    }

    /**
     * Four outcomes a caller can act on differently: it worked, the money was not there, someone
     * else got there first, or the request was wrong. Anything else is a bug and reports as error.
     */
    private static String resultOf(LedgerError error) {
        return switch (error) {
            case INSUFFICIENT_FUNDS -> "insufficient_funds";
            case IDEMPOTENCY_KEY_REUSE, TRANSACTION_ALREADY_REVERSED -> "conflict";
            default -> "invalid";
        };
    }
}
