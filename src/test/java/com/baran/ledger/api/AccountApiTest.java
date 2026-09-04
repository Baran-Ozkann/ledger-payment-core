package com.baran.ledger.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class AccountApiTest extends ApiTestSupport {

    @Test
    void accountIsCreatedEmptyAndCanBeFetched() {
        UUID account = createAccount("LIABILITY", false);

        ApiResponse response = get("/v1/accounts/" + account);

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.body().get("id")).isEqualTo(account.toString());
        assertThat(response.body().get("account_type")).isEqualTo("LIABILITY");
        assertThat(response.body().get("currency")).isEqualTo("TRY");
        assertThat(response.body().get("balance")).isEqualTo(0);
        assertThat(response.body().get("allow_negative")).isEqualTo(false);
    }

    @Test
    void unknownAccountReturnsNotFound() {
        ApiResponse response = get("/v1/accounts/" + UUID.randomUUID());

        assertThat(response.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.problemType()).isEqualTo("urn:ledger:account_not_found");
    }

    @Test
    void pageSizeOutOfRangeRejected() {
        UUID account = createAccount("LIABILITY", false);

        ApiResponse response = get("/v1/accounts/" + account + "/entries?limit=0");

        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.problemType()).isEqualTo("urn:ledger:invalid_page_size");
    }

    @Test
    void entriesPaginationStable() {
        UUID account = fundedAccount(10_000L);
        UUID destination = fundedAccount(0L);
        for (int i = 0; i < 6; i++) {
            assertThat(post("/v1/transfers", transferBody(account, destination, 100L)).status())
                    .isEqualTo(HttpStatus.CREATED);
        }

        List<Long> seen = pageThroughEntries(account, 2);

        // One funding entry plus six transfers, each seen exactly once, newest first.
        assertThat(seen).hasSize(7).doesNotHaveDuplicates().isSortedAccordingTo(Comparator.reverseOrder());
    }

    private List<Long> pageThroughEntries(UUID account, int limit) {
        List<Long> ids = new ArrayList<>();
        Long after = null;
        while (true) {
            String cursor = after == null ? "" : "&after=" + after;
            ApiResponse page = get("/v1/accounts/" + account + "/entries?limit=" + limit + cursor);

            assertThat(page.status()).isEqualTo(HttpStatus.OK);
            for (Object entry : (List<?>) page.body().get("entries")) {
                ids.add(((Number) ((Map<?, ?>) entry).get("id")).longValue());
            }

            Object nextAfter = page.body().get("next_after");
            if (nextAfter == null) {
                return ids;
            }
            after = ((Number) nextAfter).longValue();
        }
    }
}
