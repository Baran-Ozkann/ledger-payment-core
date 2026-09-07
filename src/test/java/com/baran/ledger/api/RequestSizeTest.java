package com.baran.ledger.api;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.baran.ledger.config.RequestHashFilter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The hash covers the whole body, so the whole body has to be in memory before anything about the
 * request has been checked. Unbounded, that is a way to spend the heap from outside the system: a
 * single 400 MB POST against a 256 MB heap produced an OutOfMemoryError before the cap existed.
 */
class RequestSizeTest extends ApiTestSupport {

    @Test
    void oversizedBodyIsRefusedBeforeItIsBuffered() {
        String padding = "x".repeat(RequestHashFilter.MAX_BODY_BYTES);
        String body = """
                {"account_type": "LIABILITY", "owner_ref": "%s"}""".formatted(padding);

        ApiResponse response = post("/v1/accounts", body);

        assertThat(response.status()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
        assertThat(response.problemType()).isEqualTo("urn:ledger:request_too_large");
    }

    /**
     * The cap is three orders of magnitude above anything this API accepts, so the guard must be
     * invisible to every real request. A limit that also refuses ordinary work is not a fix.
     */
    @Test
    void ordinaryRequestIsUnaffected() {
        UUID source = fundedAccount(5_000L);
        UUID destination = createAccount("LIABILITY");

        assertThat(post("/v1/transfers", transferBody(source, destination, 1_000L)).status())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(balanceOf(destination)).isEqualTo(1_000L);
    }
}
