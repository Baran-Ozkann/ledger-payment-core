package com.baran.ledger.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class TransferApiTest extends ApiTestSupport {

    @Test
    void transferHappyPath() {
        UUID source = fundedAccount(5_000L);
        UUID destination = fundedAccount(0L);

        ApiResponse response = post("/v1/transfers", transferBody(source, destination, 1_200L));

        assertThat(response.status()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.body().get("tx_type")).isEqualTo("TRANSFER");
        assertThat(amountsOf(response)).containsExactlyInAnyOrder(-1_200L, 1_200L);
        assertThat(balanceOf(source)).isEqualTo(3_800L);
        assertThat(balanceOf(destination)).isEqualTo(1_200L);

        UUID transferId = UUID.fromString((String) response.body().get("id"));
        ApiResponse fetched = get("/v1/transfers/" + transferId);

        assertThat(fetched.status()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.body().get("id")).isEqualTo(transferId.toString());
        assertThat(amountsOf(fetched)).containsExactlyInAnyOrder(-1_200L, 1_200L);
    }

    @Test
    void fundingHappyPath() {
        UUID equity = createAccount("EQUITY");
        UUID account = createAccount("LIABILITY");

        ApiResponse response = post("/v1/funding", transferBody(equity, account, 5_000L));

        assertThat(response.status()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.body().get("tx_type")).isEqualTo("FUNDING");
        assertThat(balanceOf(account)).isEqualTo(5_000L);
        // Funding is not free money: the equity account carries the other side of it.
        assertThat(balanceOf(equity)).isEqualTo(-5_000L);
    }

    @Test
    void fundingRequiresEquityToLiability() {
        UUID source = createAccount("LIABILITY");
        UUID destination = createAccount("LIABILITY");

        ApiResponse response = post("/v1/funding", transferBody(source, destination, 100L));

        assertThat(response.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.problemType()).isEqualTo("urn:ledger:invalid_funding_accounts");
    }

    @Test
    void insufficientFundsRejected() {
        UUID source = fundedAccount(100L);
        UUID destination = fundedAccount(0L);
        long transactionsBefore = transactionCount();
        long entriesBefore = entryCount();

        ApiResponse response = post("/v1/transfers", transferBody(source, destination, 500L));

        assertThat(response.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.problemType()).isEqualTo("urn:ledger:insufficient_funds");
        // The credit may run before the debit when the destination sorts first; both must roll back.
        assertThat(transactionCount()).isEqualTo(transactionsBefore);
        assertThat(entryCount()).isEqualTo(entriesBefore);
        assertThat(balanceOf(source)).isEqualTo(100L);
        assertThat(balanceOf(destination)).isZero();
    }

    @Test
    void entriesCarryTheCurrencyOfTheirAccount() {
        // The source is EQUITY because only EQUITY may go negative, and it starts empty.
        UUID source = foreignCurrencyAccount("USD", "EQUITY");
        UUID destination = foreignCurrencyAccount("USD", "LIABILITY");

        ApiResponse response = post("/v1/transfers", transferBody(source, destination, 500L));

        // Writing a constant TRY here would be refused by the I7 trigger on a USD account.
        assertThat(response.status()).isEqualTo(HttpStatus.CREATED);
        assertThat(currenciesOf(response)).containsOnly("USD");
    }

    @Test
    void unknownTransferReturnsNotFound() {
        ApiResponse response = get("/v1/transfers/" + UUID.randomUUID());

        assertThat(response.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.problemType()).isEqualTo("urn:ledger:transaction_not_found");
    }

    private static List<String> currenciesOf(ApiResponse response) {
        List<?> entries = (List<?>) response.body().get("entries");
        return entries.stream()
                .map(entry -> (String) ((Map<?, ?>) entry).get("currency"))
                .toList();
    }

    private static List<Long> amountsOf(ApiResponse response) {
        List<?> entries = (List<?>) response.body().get("entries");
        return entries.stream()
                .map(entry -> ((Number) ((Map<?, ?>) entry).get("amount")).longValue())
                .toList();
    }
}
