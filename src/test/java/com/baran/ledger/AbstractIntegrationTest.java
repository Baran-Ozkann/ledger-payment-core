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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // No broker in these tests, so neither half of the relay path is started. Left running,
        // both would spend the suite retrying a connection to a host that is not listening.
        "ledger.outbox.relay.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false",
        // Far enough out that only the run at startup happens: a reconciliation pass firing in the
        // middle of a test would count drift the test deliberately created, twice.
        "ledger.recon.interval-ms=3600000",
        // Spans are still recorded and still propagate; there is simply no collector listening in
        // a test run, and an exporter retrying one would put a stack trace under every assertion.
        "management.tracing.export.enabled=false",
        // The container hands out its own credentials and @ServiceConnection wires them into the
        // datasource, but not into Flyway, which application.yml points at the owner role. These
        // put migrations back on the container's user so both halves talk to the same database.
        "spring.flyway.user=test",
        "spring.flyway.password=test",
        // A management connector of its own, on a random port, which is the shape the application
        // actually runs in: the API on one port and actuator on another. A test that collapsed the
        // two would be asserting against a topology nothing deploys.
        "management.server.port=0"
})
public abstract class AbstractIntegrationTest {

    /** Captured before the container starts, so any Flyway row from this run must be after it. */
    static final Instant JVM_START = Instant.now();

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }
}
