package com.baran.ledger.api;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import com.baran.ledger.config.ConcurrencyPolicy;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ADR-004 comparison configuration, under test rather than only under load. A number measured
 * from a setup that quietly failed to switch isolation, or that lost money while it ran, would be
 * worse than no number at all, so both are asserted here before phase 5 quotes anything from it.
 */
@ActiveProfiles("serializable")
class SerializableConcurrencyTest extends ConcurrencyTestSupport {

    private static final int THREADS = 16;
    private static final long OPENING_BALANCE = 100_000L;
    private static final long AMOUNT = 1_000L;

    @Autowired
    ConcurrencyPolicy concurrency;

    @Test
    void profileRunsEveryTransactionAtSerializable() {
        assertThat(jdbc.sql("SELECT current_setting('transaction_isolation')").query(String.class).single())
                .isEqualTo("serializable");
        assertThat(concurrency.locksInIdOrder())
                .as("the point of the comparison is that SERIALIZABLE, not the lock order, finds the conflict")
                .isFalse();
    }

    /**
     * Every thread debits one account at the same instant with nothing ordering them. Whatever the
     * database does to them - queue them, abort and retry them, or run out of attempts - the money
     * has to add up afterwards, because a rejected attempt rolls back everything it wrote.
     *
     * <p>The assertion is written against the successes the run actually had rather than against
     * a fixed number of them. Retry exhaustion is a real outcome of this configuration and phase 5
     * is there to count it; a test that demanded it never happen would be a test that flakes.
     */
    @Test
    void concurrentDebitsOfOneAccountStayConsistentWithoutOrderedLocking() throws InterruptedException {
        UUID source = fundedAccount(OPENING_BALANCE);
        List<UUID> destinations = destinations();

        List<ApiResponse> responses = inParallel(THREADS, thread ->
                post("/v1/transfers", transferBody(source, destinations.get(thread), AMOUNT)));

        long succeeded = countOf(responses, HttpStatus.CREATED);
        assertThat(balanceOf(source)).isEqualTo(OPENING_BALANCE - succeeded * AMOUNT);
        assertThat(sumOfEntries(source)).isEqualTo(balanceOf(source));
        assertThat(transferCount()).isEqualTo(succeeded);
    }

    private List<UUID> destinations() {
        return IntStream.range(0, THREADS)
                .mapToObj(thread -> createAccount("LIABILITY"))
                .toList();
    }

    private long sumOfEntries(UUID accountPublicId) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(e.amount), 0) FROM ledger_entries e
                        JOIN accounts a ON a.id = e.account_id
                        WHERE a.public_id = ?""")
                .param(accountPublicId)
                .query(Long.class)
                .single();
    }
}
