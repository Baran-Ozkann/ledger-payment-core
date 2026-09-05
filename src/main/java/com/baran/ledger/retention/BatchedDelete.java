package com.baran.ledger.retention;

import java.util.function.IntSupplier;

/**
 * Deletes in bounded batches. One statement covering a day of rows holds its locks for as long as
 * it runs, on tables the request path is writing to; a loop of small ones lets that path through
 * in between. Each batch commits on its own, so a job that is interrupted keeps its progress.
 */
final class BatchedDelete {

    static final int BATCH_SIZE = 10_000;

    private BatchedDelete() {
    }

    /** A short batch means the table is out of matching rows, which is the only way this ends. */
    static long untilEmpty(IntSupplier batch) {
        long deleted = 0L;
        int rows;
        do {
            rows = batch.getAsInt();
            deleted += rows;
        } while (rows == BATCH_SIZE);
        return deleted;
    }
}
