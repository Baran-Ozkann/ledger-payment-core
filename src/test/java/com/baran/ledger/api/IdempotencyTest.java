package com.baran.ledger.api;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * I6 as a caller experiences it. The unique constraint is what actually enforces it; these tests
 * are about the protocol on top: which request owns the key, and what everyone else is told.
 */
class IdempotencyTest extends ConcurrencyTestSupport {

    private static final int THREADS = 100;

    @Test
    void duplicateIdempotencyKey() throws InterruptedException {
        UUID source = fundedAccount(1_000_000L);
        UUID destination = fundedAccount(0L);
        String key = UUID.randomUUID().toString();
        String body = transferBody(source, destination, 100L);

        List<ApiResponse> responses = inParallel(THREADS, thread -> post("/v1/transfers", body, CLIENT_ID, key));

        assertThat(countOf(responses, HttpStatus.CREATED)).isEqualTo(1);
        assertThat(responses).allSatisfy(response -> assertThat(response.status())
                .isIn(HttpStatus.CREATED, HttpStatus.OK, HttpStatus.CONFLICT));
        // The money moved once, no matter how many callers asked for it.
        assertThat(transferCount()).isEqualTo(1);
        assertThat(balanceOf(source)).isEqualTo(1_000_000L - 100L);
        assertThat(balanceOf(destination)).isEqualTo(100L);
        assertThat(transactionIdsOf(responses)).hasSize(1);
    }

    @Test
    void sameKeyDifferentBody() {
        UUID source = fundedAccount(1_000L);
        UUID destination = fundedAccount(0L);
        String key = UUID.randomUUID().toString();

        assertThat(post("/v1/transfers", transferBody(source, destination, 100L), CLIENT_ID, key).status())
                .isEqualTo(HttpStatus.CREATED);

        ApiResponse reused = post("/v1/transfers", transferBody(source, destination, 200L), CLIENT_ID, key);

        assertThat(reused.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(reused.problemType()).isEqualTo("urn:ledger:idempotency_key_reuse");
        assertThat(transferCount()).isEqualTo(1);
        assertThat(balanceOf(source)).isEqualTo(900L);
    }

    /**
     * The hash covers the method and the path, not only the body. Without them this second request
     * would find a completed key holding a funding's response and be answered with it: a transfer
     * that never happened, reported as a success.
     */
    @Test
    void sameKeyDifferentEndpoint() {
        UUID equity = createAccount("EQUITY");
        UUID account = createAccount("LIABILITY");
        String key = UUID.randomUUID().toString();
        String body = transferBody(equity, account, 100L);

        assertThat(post("/v1/funding", body, CLIENT_ID, key).status()).isEqualTo(HttpStatus.CREATED);

        ApiResponse transfer = post("/v1/transfers", body, CLIENT_ID, key);

        assertThat(transfer.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(transfer.problemType()).isEqualTo("urn:ledger:idempotency_key_reuse");
        assertThat(transferCount()).isZero();
    }

    /** Canonicalization: reordered keys and different whitespace are the same request. */
    @Test
    void reorderedBodyIsTheSameRequest() {
        UUID source = fundedAccount(1_000L);
        UUID destination = fundedAccount(0L);
        String key = UUID.randomUUID().toString();

        ApiResponse first = post("/v1/transfers", """
                {"from_account": "%s", "to_account": "%s", "amount": 100, "description": "phase 2"}"""
                .formatted(source, destination), CLIENT_ID, key);
        ApiResponse second = post("/v1/transfers",
                """
                        {"description":"phase 2","amount":100,"to_account":"%s","from_account":"%s"}"""
                        .formatted(destination, source), CLIENT_ID, key);

        assertThat(first.status()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.status()).isEqualTo(HttpStatus.OK);
        assertThat(second.body().get("id")).isEqualTo(first.body().get("id"));
        assertThat(transferCount()).isEqualTo(1);
        assertThat(balanceOf(source)).isEqualTo(900L);
    }

    /** A key belongs to one client, so another client's identical request is not a replay. */
    @Test
    void sameKeyUnderAnotherClient() {
        UUID source = fundedAccount(1_000L);
        UUID destination = fundedAccount(0L);
        String key = UUID.randomUUID().toString();
        String body = transferBody(source, destination, 100L);

        assertThat(post("/v1/transfers", body, "client-a", key).status()).isEqualTo(HttpStatus.CREATED);
        assertThat(post("/v1/transfers", body, "client-b", key).status()).isEqualTo(HttpStatus.CREATED);

        assertThat(transferCount()).isEqualTo(2);
        assertThat(balanceOf(source)).isEqualTo(800L);
    }

    private static Set<Object> transactionIdsOf(List<ApiResponse> responses) {
        return responses.stream()
                .filter(response -> response.body().containsKey("id"))
                .map(response -> response.body().get("id"))
                .collect(Collectors.toSet());
    }
}
