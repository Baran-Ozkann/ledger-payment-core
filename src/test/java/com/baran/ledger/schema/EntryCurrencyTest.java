package com.baran.ledger.schema;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** I7: an entry's currency matches the currency of the account it posts to. */
class EntryCurrencyTest extends SchemaTestSupport {

    @Test
    void mismatchedCurrencyRejected() {
        long accountId = insertAccount("ASSET", TRY, 0L, false);
        long transactionId = insertTransaction();

        assertThatThrownBy(() -> insertEntry(transactionId, accountId, 1_000L, "USD"))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("entry currency USD does not match account");
    }
}
