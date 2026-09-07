package com.baran.ledger.config;

import java.util.function.Supplier;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Component;

/**
 * The two answers this project weighs for keeping concurrent transfers correct, held together
 * because they are one decision rather than two settings. The default is ordered pessimistic
 * locking under READ COMMITTED: a conflicting pair asks for the same rows in the same order, so
 * one waits and nothing is ever run twice. The alternative drops the explicit locks and asks
 * SERIALIZABLE to detect the conflict instead, which aborts one of the two and requires a retry.
 *
 * <p>Neither is what makes the ledger correct. I4 is a CHECK constraint and a conditional UPDATE,
 * and the balanced-transaction trigger fires at commit whatever the isolation level is. What the
 * choice decides is who waits, how often work is thrown away, and what a caller sees when the
 * retries run out - which is why it is measured in phase 5 rather than argued about in ADR-004.
 */
@Component
public class ConcurrencyPolicy {

    private static final Logger LOG = LoggerFactory.getLogger(ConcurrencyPolicy.class);

    private final boolean orderedLocking;
    private final int maxAttempts;
    private final Counter retries;

    ConcurrencyPolicy(
            @Value("${ledger.concurrency.ordered-locking}") boolean orderedLocking,
            @Value("${ledger.concurrency.max-attempts}") int maxAttempts,
            MeterRegistry meters) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("ledger.concurrency.max-attempts must be at least 1");
        }
        this.orderedLocking = orderedLocking;
        this.maxAttempts = maxAttempts;
        this.retries = Counter.builder("ledger.serialization.retry")
                .description("Transactions the database aborted as a conflict and that were run again")
                .register(meters);
    }

    /** False only under the ADR-004 comparison profile, where SERIALIZABLE takes over the job. */
    public boolean locksInIdOrder() {
        return orderedLocking;
    }

    /**
     * Wraps the whole transaction from outside, because a transaction the database has aborted
     * cannot be continued: the retry has to be a new one. It sits inside the latency timer on
     * purpose - a caller waits through every attempt, so hiding them would report a speed nobody
     * experiences.
     *
     * <p>With the default single attempt the loop runs once and rethrows, which is what makes
     * {@code ledger_deadlock_retry_total} still mean "ordered locking failed somewhere".
     */
    public <T> T attempt(Supplier<T> work) {
        for (int attempt = 1; ; attempt++) {
            try {
                return work.get();
            } catch (ConcurrencyFailureException conflict) {
                if (attempt >= maxAttempts) {
                    throw conflict;
                }
                retries.increment();
                LOG.debug("Attempt {} of {} aborted as a conflict, running it again", attempt, maxAttempts);
            }
        }
    }
}
