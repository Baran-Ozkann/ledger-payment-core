package com.baran.ledger;

import java.time.Instant;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Every integration test class gets a fresh container. Reuse across runs was tried and
 * rejected: a warm container keeps its schema and rows between JVM invocations, which turns
 * "the table exists" and "the sum of all entries is zero" into assertions about a previous
 * run rather than this one. Verified failure mode: with reuse on, the suite passed even with
 * Flyway entirely disabled, because a prior run's flyway_schema_history was still present.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    /** Captured before the container starts, so any Flyway row from this run must be after it. */
    static final Instant JVM_START = Instant.now();

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }
}
