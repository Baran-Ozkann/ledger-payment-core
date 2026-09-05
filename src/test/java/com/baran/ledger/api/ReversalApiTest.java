package com.baran.ledger.api;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class ReversalApiTest extends ApiTestSupport {

    @Test
    void reversalCreatesCompensatingEntries() {
        UUID source = fundedAccount(5_000L);
        UUID destination = createAccount("LIABILITY");
        UUID transfer = transfer(source, destination, 1_200L);

        ApiResponse reversal = post("/v1/transfers/" + transfer + "/reversals", "");

        assertThat(reversal.status()).isEqualTo(HttpStatus.CREATED);
        assertThat(reversal.body().get("tx_type")).isEqualTo("REVERSAL");
        assertThat(balanceOf(source)).isEqualTo(5_000L);
        assertThat(balanceOf(destination)).isZero();

        UUID reversalId = UUID.fromString((String) reversal.body().get("id"));
        assertThat(amountsOf(reversalId)).containsExactlyInAnyOrder(1_200L, -1_200L);
        assertThat(amountsOf(transfer))
                .as("the original entries are untouched; the correction is a second transaction")
                .containsExactlyInAnyOrder(-1_200L, 1_200L);
        assertThat(reversesOf(reversalId)).isEqualTo(transfer);
    }

    /**
     * The money moved on and the destination cannot give it back. Refusing is the correct answer:
     * paying for the reversal out of a balance that is not there would drive the account negative,
     * which is exactly what I4 exists to prevent.
     */
    @Test
    void reversalFailsOnInsufficientFunds() {
        UUID source = fundedAccount(1_000L);
        UUID destination = createAccount("LIABILITY");
        UUID elsewhere = createAccount("LIABILITY");
        UUID transfer = transfer(source, destination, 1_000L);
        transfer(destination, elsewhere, 1_000L);

        ApiResponse reversal = post("/v1/transfers/" + transfer + "/reversals", "");

        assertThat(reversal.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(reversal.problemType()).isEqualTo("urn:ledger:insufficient_funds");
        assertThat(balanceOf(source)).isZero();
        assertThat(balanceOf(destination)).isZero();
        assertThat(balanceOf(elsewhere)).isEqualTo(1_000L);
        assertThat(reversalCountOf(transfer)).as("nothing was written by the rejected reversal").isZero();
    }

    @Test
    void reversalOfUnknownTransactionIsNotFound() {
        ApiResponse reversal = post("/v1/transfers/" + UUID.randomUUID() + "/reversals", "");

        assertThat(reversal.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(reversal.problemType()).isEqualTo("urn:ledger:transaction_not_found");
    }

    private UUID transfer(UUID from, UUID to, long amount) {
        ApiResponse response = post("/v1/transfers", transferBody(from, to, amount));
        assertThat(response.status()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) response.body().get("id"));
    }

    private List<Long> amountsOf(UUID transactionPublicId) {
        return jdbc.sql("""
                        SELECT e.amount FROM ledger_entries e
                        JOIN ledger_transactions t ON t.id = e.transaction_id
                        WHERE t.public_id = ? ORDER BY e.id""")
                .param(transactionPublicId)
                .query(Long.class)
                .list();
    }

    private UUID reversesOf(UUID reversalPublicId) {
        return jdbc.sql("""
                        SELECT original.public_id FROM ledger_transactions reversal
                        JOIN ledger_transactions original ON original.id = reversal.reverses_id
                        WHERE reversal.public_id = ?""")
                .param(reversalPublicId)
                .query(UUID.class)
                .single();
    }

    private long reversalCountOf(UUID transactionPublicId) {
        return jdbc.sql("""
                        SELECT count(*) FROM ledger_transactions reversal
                        JOIN ledger_transactions original ON original.id = reversal.reverses_id
                        WHERE original.public_id = ?""")
                .param(transactionPublicId)
                .query(Long.class)
                .single();
    }
}
