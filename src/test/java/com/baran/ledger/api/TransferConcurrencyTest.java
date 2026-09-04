package com.baran.ledger.api;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/** What the ledger does when many callers hit the same two rows at once. */
class TransferConcurrencyTest extends ConcurrencyTestSupport {

    private static final long OPENING_BALANCE = 1_000_000L;

    @Test
    void concurrentTransfersNoLostUpdate() throws InterruptedException {
        UUID source = fundedAccount(OPENING_BALANCE);
        UUID destination = fundedAccount(0L);

        List<ApiResponse> responses = inParallel(200,
                thread -> post("/v1/transfers", transferBody(source, destination, 100L)));

        assertThat(countOf(responses, HttpStatus.CREATED)).isEqualTo(200);
        // A lost update would show up here as a balance short of the full 200 x 100.
        assertThat(balanceOf(source)).isEqualTo(OPENING_BALANCE - 20_000L);
        assertThat(balanceOf(destination)).isEqualTo(20_000L);
        assertThat(transferCount()).isEqualTo(200);
    }

    /**
     * The pair that deadlocks under fixed-order locking: half the threads move A to B while the
     * other half move B to A. Locking by ascending account id makes both halves ask for the same
     * two locks in the same sequence, so they queue. A deadlock surfaces as a 500 here, because
     * PostgreSQL kills one side of the cycle and nothing retries it.
     */
    @Test
    void bidirectionalNoDeadlock() throws InterruptedException {
        UUID a = fundedAccount(OPENING_BALANCE);
        UUID b = fundedAccount(OPENING_BALANCE);

        List<ApiResponse> responses = inParallel(100, thread -> thread % 2 == 0
                ? post("/v1/transfers", transferBody(a, b, 100L))
                : post("/v1/transfers", transferBody(b, a, 100L)));

        assertThat(countOf(responses, HttpStatus.CREATED)).isEqualTo(100);
        assertThat(countOf(responses, HttpStatus.INTERNAL_SERVER_ERROR)).isZero();
        // 50 each way at the same amount nets out, which only holds if every one of them ran.
        assertThat(balanceOf(a)).isEqualTo(OPENING_BALANCE);
        assertThat(balanceOf(b)).isEqualTo(OPENING_BALANCE);
    }

    @Test
    void overdraftUnderRace() throws InterruptedException {
        UUID source = fundedAccount(1_000L);
        UUID destination = fundedAccount(0L);

        List<ApiResponse> responses = inParallel(50,
                thread -> post("/v1/transfers", transferBody(source, destination, 100L)));

        // The balance affords exactly ten of them; the conditional UPDATE is what stops the rest.
        assertThat(countOf(responses, HttpStatus.CREATED)).isEqualTo(10);
        assertThat(countOf(responses, "urn:ledger:insufficient_funds")).isEqualTo(40);
        assertThat(balanceOf(source)).isZero();
        assertThat(balanceOf(destination)).isEqualTo(1_000L);
        assertThat(transferCount()).isEqualTo(10);
    }

    @Test
    void concurrentNegativeAmountRejected() throws InterruptedException {
        UUID source = fundedAccount(1_000L);
        UUID destination = fundedAccount(0L);

        List<ApiResponse> responses = inParallel(50,
                thread -> post("/v1/transfers", transferBody(source, destination, -100L)));

        assertThat(countOf(responses, "urn:ledger:invalid_amount")).isEqualTo(50);
        assertThat(balanceOf(source)).isEqualTo(1_000L);
        assertThat(balanceOf(destination)).isZero();
        assertThat(transferCount()).isZero();
    }
}
