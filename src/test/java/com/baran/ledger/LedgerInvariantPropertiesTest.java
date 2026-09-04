package com.baran.ledger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.lifecycle.AfterContainer;
import net.jqwik.api.lifecycle.BeforeContainer;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;

import com.baran.ledger.domain.Account;
import com.baran.ledger.domain.AccountType;
import com.baran.ledger.domain.LedgerException;
import com.baran.ledger.domain.Money;
import com.baran.ledger.service.LedgerService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * I2 and I3 over random traffic. Both properties run against a database of their own: the other
 * integration tests write entries directly through SQL without materialising a balance, which is
 * legitimate for a schema test but would make a system-wide sum meaningless here.
 *
 * <p>jqwik drives its own lifecycle, so the application is started once per container rather than
 * through the Spring TestContext framework.
 */
class LedgerInvariantPropertiesTest {

    private static final int ACCOUNT_COUNT = 8;
    private static final long OPENING_BALANCE = 1_000_000L;

    private static PostgreSQLContainer<?> postgres;
    private static ConfigurableApplicationContext context;
    private static LedgerService ledger;
    private static JdbcClient jdbc;
    private static List<UUID> accounts;

    @BeforeContainer
    static void startLedger() {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
        // Passed as command line arguments, which outrank application.yml; builder properties do not.
        context = new SpringApplicationBuilder(LedgerApplication.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--spring.datasource.url=" + postgres.getJdbcUrl(),
                        "--spring.datasource.username=" + postgres.getUsername(),
                        "--spring.datasource.password=" + postgres.getPassword());
        ledger = context.getBean(LedgerService.class);
        jdbc = context.getBean(JdbcClient.class);

        Account equity = ledger.createAccount(AccountType.EQUITY, "ledger-equity");
        accounts = new ArrayList<>();
        for (int i = 0; i < ACCOUNT_COUNT; i++) {
            Account account = ledger.createAccount(AccountType.LIABILITY, "owner-" + i);
            ledger.fund(equity.publicId(), account.publicId(), Money.of(OPENING_BALANCE), "opening balance");
            accounts.add(account.publicId());
        }
    }

    @AfterContainer
    static void stopLedger() {
        if (context != null) {
            context.close();
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Property(tries = 1000)
    void everyEntryInTheSystemSumsToZero(
            @ForAll @IntRange(min = 0, max = ACCOUNT_COUNT - 1) int from,
            @ForAll @IntRange(min = 0, max = ACCOUNT_COUNT - 1) int to,
            @ForAll @LongRange(min = -5_000L, max = 5_000L) long amount) {

        attemptTransfer(from, to, amount);

        assertThat(sumOfAllEntries()).isZero();
    }

    @Property(tries = 1000)
    void everyBalanceEqualsTheSumOfItsEntries(
            @ForAll @IntRange(min = 0, max = ACCOUNT_COUNT - 1) int from,
            @ForAll @IntRange(min = 0, max = ACCOUNT_COUNT - 1) int to,
            @ForAll @LongRange(min = -5_000L, max = 5_000L) long amount) {

        attemptTransfer(from, to, amount);

        for (UUID accountId : allAccounts()) {
            assertThat(ledger.account(accountId).balance()).isEqualTo(sumOfEntriesOf(accountId).minorUnits());
        }
    }

    /**
     * Invalid amounts, self-transfers and overdrafts are part of the traffic on purpose: a rejected
     * request has to leave the ledger just as consistent as an accepted one.
     */
    private static void attemptTransfer(int from, int to, long amount) {
        try {
            ledger.transfer(accounts.get(from), accounts.get(to), Money.of(amount), "property");
        } catch (LedgerException expected) {
            // A rejection is a valid outcome; the assertion that follows is what matters.
        }
    }

    /** Includes the equity account, which never appears in the generated traffic. */
    private static List<UUID> allAccounts() {
        return jdbc.sql("SELECT public_id FROM accounts").query(UUID.class).list();
    }

    private static long sumOfAllEntries() {
        return jdbc.sql("SELECT COALESCE(SUM(amount), 0) FROM ledger_entries").query(Long.class).single();
    }

    /** Summed in Java rather than in SQL, so the check does not lean on the same engine it verifies. */
    private static Money sumOfEntriesOf(UUID accountPublicId) {
        List<Long> amounts = jdbc.sql("""
                        SELECT e.amount FROM ledger_entries e
                        JOIN accounts a ON a.id = e.account_id
                        WHERE a.public_id = ?""")
                .param(accountPublicId)
                .query(Long.class)
                .list();

        Money total = Money.of(0L);
        for (long amount : amounts) {
            total = total.plus(Money.of(amount));
        }
        return total;
    }
}
