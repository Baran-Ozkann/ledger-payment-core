package com.baran.ledger.retention;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.baran.ledger.store.IdempotencyRepository;

/**
 * Without this the table only grows, and every key in it is honoured for longer than it says.
 *
 * <p>Deleting a key means a retry of that request executes a second time, so the expiry window is
 * a statement about how late a client may retry, not a cleanup detail. It is stamped at 24 hours
 * when the key is claimed; this job only acts on what has already expired.
 */
@Component
public class IdempotencyKeyCleanupJob {

    private static final Logger LOG = LoggerFactory.getLogger(IdempotencyKeyCleanupJob.class);

    private final IdempotencyRepository keys;
    private final Counter deleted;

    IdempotencyKeyCleanupJob(IdempotencyRepository keys, MeterRegistry meters) {
        this.keys = keys;
        this.deleted = Counter.builder("ledger.cleanup.rows.deleted")
                .tag("job", "idempotency_keys")
                .description("Expired idempotency keys removed")
                .register(meters);
    }

    /** Not transactional: each batch has to commit on its own or the loop holds every lock it took. */
    @Scheduled(cron = "${ledger.retention.idempotency-keys-cron}")
    public void removeExpiredKeys() {
        long rows = BatchedDelete.untilEmpty(() -> keys.deleteExpired(BatchedDelete.BATCH_SIZE));
        deleted.increment(rows);
        LOG.info("Removed {} expired idempotency key(s)", rows);
    }
}
