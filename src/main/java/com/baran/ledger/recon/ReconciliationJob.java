package com.baran.ledger.recon;

import java.util.List;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.baran.ledger.domain.BalanceDrift;
import com.baran.ledger.store.AccountRepository;
import com.baran.ledger.store.EntryRepository;

/**
 * Checks I3 account by account and I2 across the ledger, and does nothing about what it finds.
 *
 * <p>Not correcting is the point. A balance that disagrees with its entries is the symptom of a
 * bug that has already run; silently writing the computed value over it destroys the evidence and
 * leaves the bug in place, still running. The metric and the log are the whole response.
 */
@Component
public class ReconciliationJob {

    private static final Logger LOG = LoggerFactory.getLogger(ReconciliationJob.class);

    /** One range per query, so no single statement ever pulls the whole entries table. */
    private static final long BATCH_SIZE = 10_000L;

    private final AccountRepository accounts;
    private final EntryRepository entries;
    private final Counter accountDrift;
    private final Counter systemDrift;

    ReconciliationJob(AccountRepository accounts, EntryRepository entries, MeterRegistry meters) {
        this.accounts = accounts;
        this.entries = entries;
        this.accountDrift = Counter.builder("ledger.balance.drift")
                .tag("scope", "account")
                .description("Accounts whose balance disagrees with the sum of their entries")
                .register(meters);
        this.systemDrift = Counter.builder("ledger.balance.drift")
                .tag("scope", "system")
                .description("Reconciliation passes where the entries of the whole ledger did not sum to zero")
                .register(meters);
    }

    @Scheduled(fixedDelayString = "${ledger.recon.interval-ms}")
    public void reconcile() {
        reconcileAccounts();
        reconcileLedger();
    }

    private void reconcileAccounts() {
        long highestId = accounts.maxId();
        for (long lowest = 1L; lowest <= highestId; lowest += BATCH_SIZE) {
            List<BalanceDrift> drifted = accounts.findDrift(lowest, lowest + BATCH_SIZE - 1L);
            if (!drifted.isEmpty()) {
                accountDrift.increment(drifted.size());
                LOG.error("I3 broken on {} account(s), not corrected: {}", drifted.size(), drifted);
            }
        }
    }

    /**
     * The one check a per-account scan cannot make. Two accounts can each agree with their own
     * entries while the ledger as a whole does not sum to zero, which is money created or lost.
     */
    private void reconcileLedger() {
        long sum = entries.sumOfAmounts();
        if (sum != 0L) {
            systemDrift.increment();
            LOG.error("I2 broken: the entries of the whole ledger sum to {}, not zero. Not corrected", sum);
        }
    }
}
