package com.baran.ledger.api;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.baran.ledger.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/** Requests are written as raw JSON so the tests assert on the wire format, not on a DTO. */
abstract class ApiTestSupport extends AbstractIntegrationTest {

    static final String CLIENT_ID = "phase-1b-tests";

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
            new ParameterizedTypeReference<>() {
            };

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    private RestTestClient http;

    @BeforeEach
    void bindToRunningServer() {
        http = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    record ApiResponse(HttpStatusCode status, Map<String, Object> body) {

        Object problemType() {
            return body.get("type");
        }
    }

    ApiResponse get(String uri) {
        return exchange(http.get().uri(uri));
    }

    ApiResponse post(String uri, String json) {
        return post(uri, json, CLIENT_ID);
    }

    ApiResponse post(String uri, String json, String clientId) {
        RestTestClient.RequestBodySpec request = http.post().uri(uri).contentType(MediaType.APPLICATION_JSON);
        if (clientId != null) {
            request = request.header("X-Client-Id", clientId);
        }
        return exchange(request.body(json));
    }

    static String transferBody(UUID fromAccount, UUID toAccount, long amount) {
        return """
                {"from_account": "%s", "to_account": "%s", "amount": %d, "description": "phase 1b"}"""
                .formatted(fromAccount, toAccount, amount);
    }

    UUID createAccount(String accountType, boolean allowNegative) {
        ApiResponse response = post("/v1/accounts", """
                {"account_type": "%s", "owner_ref": "owner-%s", "allow_negative": %b}"""
                .formatted(accountType, UUID.randomUUID(), allowNegative));

        assertThat(response.status()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) response.body().get("id"));
    }

    /** Money only enters the ledger through funding, so every balance starts at an EQUITY account. */
    UUID fundedAccount(long amount) {
        UUID equity = createAccount("EQUITY", true);
        UUID account = createAccount("LIABILITY", false);
        if (amount == 0L) {
            return account;
        }

        assertThat(post("/v1/funding", transferBody(equity, account, amount)).status())
                .isEqualTo(HttpStatus.CREATED);
        return account;
    }

    /**
     * The ledger is single currency, so the API has no way to create anything but a TRY account.
     * A foreign-currency account is inserted directly, which is the only way to reach V7 at all.
     */
    UUID foreignCurrencyAccount(String currency, boolean allowNegative) {
        UUID publicId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO accounts (public_id, account_type, owner_ref, currency, allow_negative)
                        VALUES (?, 'LIABILITY', ?, ?, ?)""")
                .params(publicId, "owner-" + publicId, currency, allowNegative)
                .update();
        return publicId;
    }

    long balanceOf(UUID accountPublicId) {
        return jdbc.sql("SELECT balance FROM accounts WHERE public_id = ?")
                .param(accountPublicId)
                .query(Long.class)
                .single();
    }

    long transactionCount() {
        return jdbc.sql("SELECT count(*) FROM ledger_transactions").query(Long.class).single();
    }

    long entryCount() {
        return jdbc.sql("SELECT count(*) FROM ledger_entries").query(Long.class).single();
    }

    private static ApiResponse exchange(RestTestClient.RequestHeadersSpec<?> request) {
        EntityExchangeResult<Map<String, Object>> result = request.exchange().returnResult(JSON_OBJECT);
        return new ApiResponse(result.getStatus(), result.getResponseBody());
    }
}
