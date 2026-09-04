package com.baran.ledger.api;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.baran.ledger.domain.Money;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1 to V5. None of these is covered by an invariant: a negative amount inverts the transfer and
 * creates money while every database-level defense still passes, so each rule is checked here and
 * each rejection is asserted to have written nothing at all.
 */
class TransferValidationTest extends ApiTestSupport {

    @Test
    void negativeAmountRejected() {
        UUID source = fundedAccount(1_000L);
        UUID destination = fundedAccount(0L);
        long transactionsBefore = transactionCount();
        long entriesBefore = entryCount();

        ApiResponse response = post("/v1/transfers", transferBody(source, destination, -100L));

        assertThat(response.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.problemType()).isEqualTo("urn:ledger:invalid_amount");
        assertThat(transactionCount()).isEqualTo(transactionsBefore);
        assertThat(entryCount()).isEqualTo(entriesBefore);
        assertThat(balanceOf(source)).isEqualTo(1_000L);
        assertThat(balanceOf(destination)).isZero();
    }

    @Test
    void zeroAmountRejected() {
        UUID source = fundedAccount(1_000L);
        UUID destination = fundedAccount(0L);
        long entriesBefore = entryCount();

        ApiResponse response = post("/v1/transfers", transferBody(source, destination, 0L));

        assertThat(response.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.problemType()).isEqualTo("urn:ledger:invalid_amount");
        assertThat(entryCount()).isEqualTo(entriesBefore);
    }

    @Test
    void oversizedAmountRejected() {
        UUID source = fundedAccount(1_000L);
        UUID destination = fundedAccount(0L);
        long entriesBefore = entryCount();

        ApiResponse response = post("/v1/transfers", transferBody(source, destination, Money.MAX_AMOUNT + 1));

        assertThat(response.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.problemType()).isEqualTo("urn:ledger:amount_too_large");
        assertThat(entryCount()).isEqualTo(entriesBefore);
    }

    @Test
    void selfTransferRejected() {
        UUID account = fundedAccount(1_000L);
        long transactionsBefore = transactionCount();
        long entriesBefore = entryCount();

        ApiResponse response = post("/v1/transfers", transferBody(account, account, 100L));

        assertThat(response.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.problemType()).isEqualTo("urn:ledger:self_transfer");
        assertThat(transactionCount()).isEqualTo(transactionsBefore);
        assertThat(entryCount()).isEqualTo(entriesBefore);
        assertThat(balanceOf(account)).isEqualTo(1_000L);
    }

    /**
     * V7. Every invariant holds on a cross-currency transfer: the two entries sum to zero and each
     * one matches the currency of its own account, so I1, I2 and I7 all pass while 1000 kurus
     * leave one account and 1000 cents arrive in another. Nothing but this rule stops it.
     */
    @Test
    void crossCurrencyTransferRejected() {
        UUID source = fundedAccount(1_000L);
        UUID destination = foreignCurrencyAccount("USD", "LIABILITY");
        long transactionsBefore = transactionCount();
        long entriesBefore = entryCount();

        ApiResponse response = post("/v1/transfers", transferBody(source, destination, 100L));

        assertThat(response.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.problemType()).isEqualTo("urn:ledger:currency_mismatch");
        assertThat(transactionCount()).isEqualTo(transactionsBefore);
        assertThat(entryCount()).isEqualTo(entriesBefore);
        assertThat(balanceOf(source)).isEqualTo(1_000L);
        assertThat(balanceOf(destination)).isZero();
    }

    @Test
    void decimalAmountRejected() {
        UUID source = fundedAccount(1_000L);
        UUID destination = fundedAccount(0L);
        long entriesBefore = entryCount();

        ApiResponse response = post("/v1/transfers", """
                {"from_account": "%s", "to_account": "%s", "amount": 1250.00}"""
                .formatted(source, destination));

        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(entryCount()).isEqualTo(entriesBefore);
    }

    @Test
    void missingClientIdRejected() {
        UUID source = fundedAccount(1_000L);
        UUID destination = fundedAccount(0L);
        long entriesBefore = entryCount();

        ApiResponse response = post("/v1/transfers", transferBody(source, destination, 100L), null);

        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.problemType()).isEqualTo("urn:ledger:missing_client_id");
        assertThat(entryCount()).isEqualTo(entriesBefore);
    }
}
