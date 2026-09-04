package com.baran.ledger.service;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.baran.ledger.AbstractIntegrationTest;
import com.baran.ledger.domain.Account;
import com.baran.ledger.domain.AccountType;
import com.baran.ledger.domain.LedgerEntry;
import com.baran.ledger.domain.LedgerError;
import com.baran.ledger.domain.LedgerException;
import com.baran.ledger.domain.LedgerTransaction;
import com.baran.ledger.domain.Money;
import com.baran.ledger.domain.TxType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerServiceTest extends AbstractIntegrationTest {

    @Autowired
    LedgerService ledger;

    @Test
    void transferMovesMoneyBetweenAccounts() {
        Account source = fundedAccount(5_000L);
        Account destination = ledger.createAccount(AccountType.LIABILITY, "owner", false);

        LedgerTransaction transaction = ledger.transfer(
                source.publicId(), destination.publicId(), Money.of(1_200L), "salary");

        assertThat(transaction.txType()).isEqualTo(TxType.TRANSFER);
        assertThat(ledger.account(source.publicId()).balance()).isEqualTo(3_800L);
        assertThat(ledger.account(destination.publicId()).balance()).isEqualTo(1_200L);
        assertThat(ledger.entriesOfTransaction(transaction.publicId()))
                .extracting(LedgerEntry::amount)
                .containsExactlyInAnyOrder(-1_200L, 1_200L);
    }

    @Test
    void amountIsValidatedBeforeAnyAccountIsRead() {
        // Both accounts are unknown, so an ACCOUNT_NOT_FOUND here would mean V1 ran too late.
        assertThatThrownBy(() -> ledger.transfer(UUID.randomUUID(), UUID.randomUUID(), Money.of(-100L), null))
                .isInstanceOf(LedgerException.class)
                .extracting(exception -> ((LedgerException) exception).error())
                .isEqualTo(LedgerError.INVALID_AMOUNT);
    }

    @Test
    void unknownAccountIsRejected() {
        Account source = fundedAccount(1_000L);

        assertThatThrownBy(() -> ledger.transfer(source.publicId(), UUID.randomUUID(), Money.of(100L), null))
                .isInstanceOf(LedgerException.class)
                .extracting(exception -> ((LedgerException) exception).error())
                .isEqualTo(LedgerError.ACCOUNT_NOT_FOUND);
    }

    private Account fundedAccount(long amount) {
        Account equity = ledger.createAccount(AccountType.EQUITY, "ledger-equity", true);
        Account account = ledger.createAccount(AccountType.LIABILITY, "owner", false);
        ledger.fund(equity.publicId(), account.publicId(), Money.of(amount), "opening balance");
        return ledger.account(account.publicId());
    }
}
