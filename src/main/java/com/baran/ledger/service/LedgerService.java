package com.baran.ledger.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baran.ledger.domain.Account;
import com.baran.ledger.domain.AccountType;
import com.baran.ledger.domain.LedgerEntry;
import com.baran.ledger.domain.LedgerError;
import com.baran.ledger.domain.LedgerException;
import com.baran.ledger.domain.LedgerTransaction;
import com.baran.ledger.domain.Money;
import com.baran.ledger.domain.TxType;
import com.baran.ledger.store.AccountRepository;
import com.baran.ledger.store.EntryRepository;
import com.baran.ledger.store.TransactionRepository;

@Service
public class LedgerService {

    public static final int MAX_PAGE_SIZE = 200;

    private final AccountRepository accounts;
    private final TransactionRepository transactions;
    private final EntryRepository entries;

    LedgerService(AccountRepository accounts, TransactionRepository transactions, EntryRepository entries) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.entries = entries;
    }

    @Transactional
    public Account createAccount(AccountType accountType, String ownerRef) {
        UUID publicId = UUID.randomUUID();
        accounts.insert(publicId, accountType, ownerRef);
        return account(publicId);
    }

    public Account account(UUID publicId) {
        return accounts.findByPublicId(publicId)
                .orElseThrow(() -> new LedgerException(LedgerError.ACCOUNT_NOT_FOUND));
    }

    public LedgerTransaction transaction(UUID publicId) {
        return transactions.findByPublicId(publicId)
                .orElseThrow(() -> new LedgerException(LedgerError.TRANSACTION_NOT_FOUND));
    }

    public List<LedgerEntry> entriesOfTransaction(UUID publicId) {
        return entries.findByTransaction(transaction(publicId).id());
    }

    public List<LedgerEntry> entriesOfAccount(UUID publicId, Long after, int limit) {
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new LedgerException(LedgerError.INVALID_PAGE_SIZE);
        }
        return entries.findByAccount(account(publicId).id(), after, limit);
    }

    @Transactional
    public LedgerTransaction transfer(UUID fromAccount, UUID toAccount, Money amount, String description) {
        return post(TxType.TRANSFER, fromAccount, toAccount, amount, description);
    }

    @Transactional
    public LedgerTransaction fund(UUID fromAccount, UUID toAccount, Money amount, String description) {
        return post(TxType.FUNDING, fromAccount, toAccount, amount, description);
    }

    /**
     * V1 to V3 are checked before anything is read or written, and V7 as soon as both accounts are
     * known, because none of them is covered by an invariant. A negative amount inverts the
     * transfer, and a cross-currency pair balances to zero with each entry matching its own
     * account; both create money while every database-level defense still passes.
     */
    private LedgerTransaction post(TxType txType, UUID fromAccount, UUID toAccount, Money amount, String description) {
        if (!amount.isPositive()) {
            throw new LedgerException(LedgerError.INVALID_AMOUNT);
        }
        if (amount.exceedsMaximum()) {
            throw new LedgerException(LedgerError.AMOUNT_TOO_LARGE);
        }
        if (fromAccount.equals(toAccount)) {
            throw new LedgerException(LedgerError.SELF_TRANSFER);
        }

        Account source = account(fromAccount);
        Account destination = account(toAccount);
        if (!source.currency().equals(destination.currency())) {
            throw new LedgerException(LedgerError.CURRENCY_MISMATCH);
        }
        if (txType == TxType.FUNDING && !isFundingPair(source, destination)) {
            throw new LedgerException(LedgerError.INVALID_FUNDING_ACCOUNTS);
        }

        UUID publicId = UUID.randomUUID();
        long transactionId = transactions.insert(publicId, txType, description);
        moveBalances(source, destination, amount);
        entries.insert(transactionId, source.id(), amount.negated().minorUnits(), source.currency());
        entries.insert(transactionId, destination.id(), amount.minorUnits(), destination.currency());
        return transaction(publicId);
    }

    /**
     * Both rows are touched in ascending internal id order. The two UPDATEs take row locks, so
     * with a fixed order a pair of opposing transfers can deadlock instead of queueing.
     */
    private void moveBalances(Account source, Account destination, Money amount) {
        if (source.id() < destination.id()) {
            debit(source, amount);
            accounts.credit(destination.id(), amount.minorUnits());
        } else {
            accounts.credit(destination.id(), amount.minorUnits());
            debit(source, amount);
        }
    }

    private void debit(Account source, Money amount) {
        if (accounts.debit(source.id(), amount.minorUnits()) == 0) {
            throw new LedgerException(LedgerError.INSUFFICIENT_FUNDS);
        }
    }

    private static boolean isFundingPair(Account source, Account destination) {
        return source.accountType() == AccountType.EQUITY && destination.accountType() == AccountType.LIABILITY;
    }
}
