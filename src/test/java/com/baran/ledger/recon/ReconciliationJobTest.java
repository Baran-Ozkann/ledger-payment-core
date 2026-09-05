package com.baran.ledger.recon;

import java.util.UUID;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.baran.ledger.AbstractIntegrationTest;
import com.baran.ledger.domain.Account;
import com.baran.ledger.domain.AccountType;
import com.baran.ledger.domain.Money;
import com.baran.ledger.service.LedgerService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drift is created by writing a balance straight onto the account row, which is the one way it can
 * happen: nothing in the service can produce it, because the balance and the entries are written
 * by the same transaction. Each test puts the balance back, so the next one starts from a ledger
 * that reconciles.
 */
class ReconciliationJobTest extends AbstractIntegrationTest {

    @Autowired
    ReconciliationJob reconciliation;

    @Autowired
    LedgerService ledger;

    @Autowired
    MeterRegistry meters;

    @Autowired
    JdbcClient jdbc;

    @Test
    void reconciliationDetectsDrift() {
        Account account = fundedAccount(1_000L);
        double before = driftCount();

        corruptBalance(account.publicId(), 1_500L);
        reconciliation.reconcile();

        assertThat(driftCount() - before).as("one account is reported, once").isEqualTo(1.0d);
        corruptBalance(account.publicId(), 1_000L);
    }

    @Test
    void reconciliationDoesNotAutoFix() {
        Account account = fundedAccount(1_000L);
        corruptBalance(account.publicId(), 1_500L);

        reconciliation.reconcile();

        assertThat(balanceOf(account.publicId()))
                .as("the wrong balance is still wrong: correcting it would erase the evidence")
                .isEqualTo(1_500L);
        assertThat(entrySumOf(account.publicId())).isEqualTo(1_000L);
        corruptBalance(account.publicId(), 1_000L);
    }

    /**
     * The system-wide half of the job. Its alarm branch cannot be reached from here on purpose:
     * the deferred constraint trigger refuses any transaction whose entries do not sum to zero, so
     * I2 cannot be broken through any supported path. What is testable is that the check runs over
     * a real ledger and stays quiet, which is also the state it is expected to report forever.
     */
    @Test
    void reconciliationIsQuietOnALedgerThatAgrees() {
        fundedAccount(2_500L);
        double before = driftCount();

        reconciliation.reconcile();

        assertThat(driftCount()).isEqualTo(before);
        assertThat(meters.get("ledger.balance.drift").tag("scope", "system").counter().count())
                .as("every entry ever written by this suite still sums to zero")
                .isZero();
    }

    private double driftCount() {
        return meters.get("ledger.balance.drift").tag("scope", "account").counter().count();
    }

    /** Allowed on accounts, unlike ledger_entries: only the ledger itself is append-only. */
    private void corruptBalance(UUID accountPublicId, long balance) {
        jdbc.sql("UPDATE accounts SET balance = ? WHERE public_id = ?")
                .params(balance, accountPublicId)
                .update();
    }

    private long balanceOf(UUID accountPublicId) {
        return jdbc.sql("SELECT balance FROM accounts WHERE public_id = ?")
                .param(accountPublicId)
                .query(Long.class)
                .single();
    }

    private long entrySumOf(UUID accountPublicId) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(e.amount), 0) FROM ledger_entries e
                        JOIN accounts a ON a.id = e.account_id
                        WHERE a.public_id = ?""")
                .param(accountPublicId)
                .query(Long.class)
                .single();
    }

    private Account fundedAccount(long amount) {
        Account equity = ledger.createAccount(AccountType.EQUITY, "ledger-equity");
        Account account = ledger.createAccount(AccountType.LIABILITY, "owner");
        ledger.fund(equity.publicId(), account.publicId(), Money.of(amount), "opening balance");
        return ledger.account(account.publicId());
    }
}
