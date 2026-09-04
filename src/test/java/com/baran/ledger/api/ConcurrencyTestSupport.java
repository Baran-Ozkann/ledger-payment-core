package com.baran.ledger.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.HttpStatusCode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No test transaction anywhere in here: one would roll back the very writes the threads are racing
 * over, and hide the concurrency being measured. Each test empties the tables instead. TRUNCATE
 * rather than DELETE, because I5 forbids deleting a ledger row and a row trigger skips a truncate.
 */
abstract class ConcurrencyTestSupport extends ApiTestSupport {

    private static final int COMPLETION_TIMEOUT_SECONDS = 120;

    @BeforeEach
    void emptyTheLedger() {
        jdbc.sql("""
                        TRUNCATE idempotency_keys, ledger_entries, ledger_transactions, accounts
                        RESTART IDENTITY""")
                .update();
    }

    /**
     * Every thread parks on the same latch and is released by a single countDown, so the requests
     * genuinely overlap. Starting the threads one by one would let the first finish before the
     * last begins, and the test would prove nothing about concurrency.
     *
     * @param request receives the thread index, so a test can send different requests per thread
     */
    List<ApiResponse> inParallel(int threads, IntFunction<ApiResponse> request) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(threads);
        List<ApiResponse> responses = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

        try {
            for (int thread = 0; thread < threads; thread++) {
                int index = thread;
                pool.submit(() -> {
                    try {
                        start.await();
                        responses.add(request.apply(index));
                    } catch (Throwable failure) {
                        failures.add(failure);
                    } finally {
                        finished.countDown();
                    }
                });
            }

            start.countDown();
            assertThat(finished.await(COMPLETION_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .as("all %d requests completed", threads)
                    .isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(failures).as("requests that never produced a response").isEmpty();
        assertThat(responses).hasSize(threads);
        return responses;
    }

    static long countOf(List<ApiResponse> responses, HttpStatusCode status) {
        return responses.stream().filter(response -> response.status().equals(status)).count();
    }

    static long countOf(List<ApiResponse> responses, String problemType) {
        return responses.stream().filter(response -> problemType.equals(response.problemType())).count();
    }
}
