package com.baran.ledger;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Every integration test shares one PostgreSQL container for the whole JVM. A per-class
 * container would restart the database between test classes and cost more than the tests
 * themselves; reuse keeps it alive across local runs as well.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine").withReuse(true);

    static {
        POSTGRES.start();
    }
}
