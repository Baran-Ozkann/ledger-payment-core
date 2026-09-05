package com.baran.ledger.retention;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.baran.ledger.store.OutboxRepository;

/**
 * Published outbox rows have done their job and the table would otherwise grow forever.
 *
 * <p>Seven days: long enough to answer "was this event ever sent, and when" while a delivery
 * problem is being investigated, short enough to bound the table. The filter is `published_at`,
 * never `created_at`. On `created_at` this job would delete events that were never sent, and an
 * event deleted before publication is gone with nothing left to notice it by.
 */
@Component
public class OutboxArchivalJob {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxArchivalJob.class);

    private static final int RETENTION_DAYS = 7;

    private final OutboxRepository outbox;
    private final Counter deleted;

    OutboxArchivalJob(OutboxRepository outbox, MeterRegistry meters) {
        this.outbox = outbox;
        this.deleted = Counter.builder("ledger.cleanup.rows.deleted")
                .tag("job", "outbox_events")
                .description("Published outbox events removed")
                .register(meters);
    }

    /** Not transactional: each batch has to commit on its own or the loop holds every lock it took. */
    @Scheduled(cron = "${ledger.retention.outbox-cron}")
    public void archivePublished() {
        long rows = BatchedDelete.untilEmpty(
                () -> outbox.deletePublishedBefore(RETENTION_DAYS, BatchedDelete.BATCH_SIZE));
        deleted.increment(rows);
        LOG.info("Removed {} outbox event(s) published more than {} days ago", rows, RETENTION_DAYS);
    }
}
