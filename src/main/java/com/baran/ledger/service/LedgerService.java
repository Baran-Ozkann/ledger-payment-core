package com.baran.ledger.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import com.baran.ledger.config.ConcurrencyPolicy;
import com.baran.ledger.config.TraceContexts;
import com.baran.ledger.config.TransferMetrics;
import com.baran.ledger.domain.Account;
import com.baran.ledger.domain.AccountActivityEvent;
import com.baran.ledger.domain.AccountType;
import com.baran.ledger.domain.EntryPosting;
import com.baran.ledger.domain.IdempotencyRecord;
import com.baran.ledger.domain.IdempotencyRequest;
import com.baran.ledger.domain.LedgerEntry;
import com.baran.ledger.domain.LedgerError;
import com.baran.ledger.domain.LedgerException;
import com.baran.ledger.domain.LedgerTransaction;
import com.baran.ledger.domain.Money;
import com.baran.ledger.domain.TxType;
import com.baran.ledger.store.AccountRepository;
import com.baran.ledger.store.EntryRepository;
import com.baran.ledger.store.IdempotencyRepository;
import com.baran.ledger.store.OutboxRepository;
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
    private final OutboxRepository outbox;
    private final TransferMetrics metrics;
    private final TraceContexts traces;
    private final ConcurrencyPolicy concurrency;
    private final ObjectMapper json;

    LedgerService(AccountRepository accounts, TransactionRepository transactions, EntryRepository entries,
                  IdempotencyRepository idempotency, OutboxRepository outbox, TransferMetrics metrics,
                  TraceContexts traces, ConcurrencyPolicy concurrency, ObjectMapper json) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.entries = entries;
        this.idempotency = idempotency;
        this.outbox = outbox;
        this.metrics = metrics;
        this.traces = traces;
        this.concurrency = concurrency;
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

    @Transactional
    public IdempotentOutcome reverse(
            IdempotencyRequest request, UUID transactionPublicId, ResponseView<LedgerTransaction> view) {
        return idempotently(request, () -> completionOf(reverse(transactionPublicId), view));
    }

    /**
     * A reversal is a new transaction carrying the original's entries with their signs flipped.
     * The original is never touched: I5 forbids it, and a correction that edits history destroys
     * the evidence of what actually happened.
     *
     * <p>It can be refused. Giving money back to an account whose counterpart has since spent it
     * would drive that counterpart negative, so the flipped entries go through the same conditional
     * UPDATE an ordinary debit does. A reversal is not privileged over I4.
     */
    @Transactional
    public LedgerTransaction reverse(UUID transactionPublicId) {
        LedgerTransaction original = transaction(transactionPublicId);
        List<EntryPosting> postings = entries.postingsOf(original.id());

        lockInIdOrder(postings.stream().map(EntryPosting::accountId).toList());

        UUID publicId = UUID.randomUUID();
        long reversalId = insertReversal(publicId, original);
        for (EntryPosting posting : postings) {
            long flipped = Math.negateExact(posting.amount());
            applyToBalance(posting.accountId(), flipped);
            entries.insert(reversalId, posting.accountId(), flipped, posting.currency());
        }

        LedgerTransaction reversal = transaction(publicId);
        postings.forEach(posting -> announce(
                reversal, posting.accountPublicId(), Math.negateExact(posting.amount()), posting.currency()));
        return reversal;
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
     * Reached when the claim found the key already taken. A row that is visible here is always a
     * finished one: the claim commits with the ledger write, so an attempt that fails takes its own
     * claim down with it. The stored hash is what separates a retry of the same request from a
     * second, different request wearing the same key.
     */
    private IdempotentOutcome replayOf(IdempotencyRequest request) {
        metrics.idempotencyHit();
        IdempotencyRecord record = idempotency.find(request.clientId(), request.key())
                .orElseThrow(() -> new IllegalStateException(
                        "the key was claimed by someone else but no row is visible: " + request.key()));

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

        lockInIdOrder(List.of(source.id(), destination.id()));

        UUID publicId = UUID.randomUUID();
        long transactionId = transactions.insert(publicId, txType, description);
        debit(source.id(), amount.minorUnits());
        accounts.credit(destination.id(), amount.minorUnits());
        entries.insert(transactionId, source.id(), amount.negated().minorUnits(), source.currency());
        entries.insert(transactionId, destination.id(), amount.minorUnits(), destination.currency());

        LedgerTransaction transaction = transaction(publicId);
        announce(transaction, source.publicId(), amount.negated().minorUnits(), source.currency());
        announce(transaction, destination.publicId(), amount.minorUnits(), destination.currency());
        return transaction;
    }

    /**
     * The event is a row this transaction writes, not a call to a broker. Calling a broker from
     * here would announce transfers that then roll back, and lose the ones that commit while the
     * broker is unreachable; the row cannot disagree with the entries it was written beside.
     *
     * <p>One event per entry, keyed by the account: the account is the aggregate a consumer cares
     * about, and it is what the partition key has to be for per-account ordering to mean anything.
     */
    private void announce(LedgerTransaction transaction, UUID accountPublicId, long amount, String currency) {
        AccountActivityEvent event = new AccountActivityEvent(
                transaction.publicId(), accountPublicId, amount, currency, transaction.txType());
        outbox.append(
                AccountActivityEvent.AGGREGATE_TYPE,
                accountPublicId.toString(),
                AccountActivityEvent.EVENT_TYPE,
                json.writeValueAsString(event),
                traces.current());
    }

    /**
     * Every row is locked before any of them is written, in ascending internal id order. Ordering
     * by id rather than by role is the whole point: two opposing transfers between the same pair
     * ask for the same locks in the same sequence, so one waits instead of the two deadlocking.
     * A reversal takes the same route for the same reason.
     *
     * <p>The one configuration that skips this is the ADR-004 comparison, where SERIALIZABLE is
     * asked to find the same conflicts by itself. Skipping the locks does not weaken I4: the debit
     * is still a conditional UPDATE and the CHECK constraint is still on the column.
     */
    private void lockInIdOrder(List<Long> accountIds) {
        if (!concurrency.locksInIdOrder()) {
            return;
        }
        accountIds.stream().sorted().forEach(accounts::lock);
    }

    private long insertReversal(UUID publicId, LedgerTransaction original) {
        try {
            return transactions.insertReversal(publicId, original.id(), "Reversal of " + original.publicId());
        } catch (DuplicateKeyException alreadyReversed) {
            throw new LedgerException(LedgerError.TRANSACTION_ALREADY_REVERSED);
        }
    }

    private void applyToBalance(long accountId, long amount) {
        if (amount < 0L) {
            debit(accountId, Math.negateExact(amount));
        } else {
            accounts.credit(accountId, amount);
        }
    }

    private void debit(long accountId, long amount) {
        if (accounts.debit(accountId, amount) == 0) {
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
