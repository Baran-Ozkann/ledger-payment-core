package com.baran.ledger.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import com.baran.ledger.domain.Account;
import com.baran.ledger.domain.AccountType;
import com.baran.ledger.domain.IdempotencyRecord;
import com.baran.ledger.domain.IdempotencyRequest;
import com.baran.ledger.domain.IdempotencyStatus;
import com.baran.ledger.domain.LedgerEntry;
import com.baran.ledger.domain.LedgerError;
import com.baran.ledger.domain.LedgerException;
import com.baran.ledger.domain.LedgerTransaction;
import com.baran.ledger.domain.Money;
import com.baran.ledger.domain.TxType;
import com.baran.ledger.store.AccountRepository;
import com.baran.ledger.store.EntryRepository;
import com.baran.ledger.store.IdempotencyRepository;
import com.baran.ledger.store.TransactionRepository;

@Service
public class LedgerService {

    public static final int MAX_PAGE_SIZE = 200;

    /** The code a first execution answers with, stored so that a repeat of it answers the same. */
    private static final int CREATED = 201;

    private final AccountRepository accounts;
    private final TransactionRepository transactions;
    private final EntryRepository entries;
    private final IdempotencyRepository idempotency;
    private final ObjectMapper json;

    LedgerService(AccountRepository accounts, TransactionRepository transactions, EntryRepository entries,
                  IdempotencyRepository idempotency, ObjectMapper json) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.entries = entries;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Transactional
    public IdempotentOutcome createAccount(
            IdempotencyRequest request, AccountType accountType, String ownerRef, ResponseView<Account> view) {
        return idempotently(request, () -> new Completion(view.render(createAccount(accountType, ownerRef)), null));
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
    public IdempotentOutcome transfer(
            IdempotencyRequest request, UUID fromAccount, UUID toAccount, Money amount, String description,
            ResponseView<LedgerTransaction> view) {
        return idempotently(request, () -> completionOf(transfer(fromAccount, toAccount, amount, description), view));
    }

    @Transactional
    public LedgerTransaction transfer(UUID fromAccount, UUID toAccount, Money amount, String description) {
        return post(TxType.TRANSFER, fromAccount, toAccount, amount, description);
    }

    @Transactional
    public IdempotentOutcome fund(
            IdempotencyRequest request, UUID fromAccount, UUID toAccount, Money amount, String description,
            ResponseView<LedgerTransaction> view) {
        return idempotently(request, () -> completionOf(fund(fromAccount, toAccount, amount, description), view));
    }

    @Transactional
    public LedgerTransaction fund(UUID fromAccount, UUID toAccount, Money amount, String description) {
        return post(TxType.FUNDING, fromAccount, toAccount, amount, description);
    }

    /**
     * The claim, the ledger write and the stored response commit together. A caller holding a
     * response therefore knows the key is durably taken, and a rejection releases the key with the
     * money it would have moved, so nothing is left half done for a retry to trip over.
     */
    private IdempotentOutcome idempotently(IdempotencyRequest request, Supplier<Completion> work) {
        Optional<Long> claim = idempotency.claim(request);
        if (claim.isEmpty()) {
            return replayOf(request);
        }

        Completion completion = work.get();
        String body = json.writeValueAsString(completion.view());
        idempotency.complete(claim.get(), CREATED, body, completion.transactionId());
        return new IdempotentOutcome(CREATED, body);
    }

    /**
     * Reached when the claim found the key already taken. The stored hash is what separates a
     * retry of the same request from a second, different request wearing the same key.
     */
    private IdempotentOutcome replayOf(IdempotencyRequest request) {
        IdempotencyRecord record = idempotency.find(request.clientId(), request.key())
                .orElseThrow(() -> new IllegalStateException(
                        "the key was claimed by someone else but no row is visible: " + request.key()));

        if (record.status() == IdempotencyStatus.IN_PROGRESS) {
            throw new LedgerException(LedgerError.REQUEST_IN_PROGRESS);
        }
        if (!record.requestHash().equals(request.requestHash())) {
            throw new LedgerException(LedgerError.IDEMPOTENCY_KEY_REUSE);
        }
        return new IdempotentOutcome(record.responseCode(), record.responseBody());
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

        lockInIdOrder(source, destination);

        UUID publicId = UUID.randomUUID();
        long transactionId = transactions.insert(publicId, txType, description);
        debit(source, amount);
        accounts.credit(destination.id(), amount.minorUnits());
        entries.insert(transactionId, source.id(), amount.negated().minorUnits(), source.currency());
        entries.insert(transactionId, destination.id(), amount.minorUnits(), destination.currency());
        return transaction(publicId);
    }

    /**
     * Both rows are locked before either is written, in ascending internal id order. Ordering by id
     * rather than by role is the whole point: two opposing transfers between the same pair ask for
     * the same two locks in the same sequence, so one waits instead of the two deadlocking.
     */
    private void lockInIdOrder(Account source, Account destination) {
        accounts.lock(Math.min(source.id(), destination.id()));
        accounts.lock(Math.max(source.id(), destination.id()));
    }

    private void debit(Account source, Money amount) {
        if (accounts.debit(source.id(), amount.minorUnits()) == 0) {
            throw new LedgerException(LedgerError.INSUFFICIENT_FUNDS);
        }
    }

    private static Completion completionOf(LedgerTransaction transaction, ResponseView<LedgerTransaction> view) {
        return new Completion(view.render(transaction), transaction.id());
    }

    private static boolean isFundingPair(Account source, Account destination) {
        return source.accountType() == AccountType.EQUITY && destination.accountType() == AccountType.LIABILITY;
    }

    /** @param transactionId null for an operation that writes no ledger transaction */
    private record Completion(Object view, Long transactionId) {
    }
}
