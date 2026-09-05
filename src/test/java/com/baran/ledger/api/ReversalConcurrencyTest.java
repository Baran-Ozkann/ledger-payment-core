package com.baran.ledger.api;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class ReversalConcurrencyTest extends ConcurrencyTestSupport {

    private static final int THREADS = 20;

    /**
     * Every thread asks to reverse the same transaction at the same moment. uq_single_reversal is
     * what decides: the losers block on the index until the winner commits and are then refused by
     * the database, not by a check they could all have passed at once.
     */
    @Test
    void doubleReversalRejected() throws InterruptedException {
        UUID source = fundedAccount(10_000L);
        UUID destination = fundedAccount(0L);
        UUID transfer = transfer(source, destination, 1_000L);

        List<ApiResponse> responses = inParallel(THREADS,
                thread -> post("/v1/transfers/" + transfer + "/reversals", ""));

        assertThat(countOf(responses, HttpStatus.CREATED)).isEqualTo(1);
        assertThat(countOf(responses, "urn:ledger:transaction_already_reversed")).isEqualTo(THREADS - 1);
        assertThat(countOf(responses, HttpStatus.INTERNAL_SERVER_ERROR)).isZero();
        // Applied once, so the transfer is undone exactly once and no further.
        assertThat(balanceOf(source)).isEqualTo(10_000L);
        assertThat(balanceOf(destination)).isZero();
        assertThat(reversalCount()).isEqualTo(1);
    }

    private UUID transfer(UUID from, UUID to, long amount) {
        ApiResponse response = post("/v1/transfers", transferBody(from, to, amount));
        assertThat(response.status()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) response.body().get("id"));
    }

    private long reversalCount() {
        return jdbc.sql("SELECT count(*) FROM ledger_transactions WHERE tx_type = 'REVERSAL'")
                .query(Long.class)
                .single();
    }
}
