package com.baran.ledger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

class SmokeTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbcClient;

    @Test
    void applicationContextStarts() {
        assertThat(jdbcClient).isNotNull();
    }

    @Test
    void flywayAppliedTheBaselineMigration() {
        // A row existing proves nothing on a reused container - it could be left over from an
        // earlier run. Asserting installed_on is after this JVM started proves Flyway ran now.
        Timestamp installedOn = jdbcClient
                .sql("SELECT installed_on FROM flyway_schema_history WHERE version = '1' AND success")
                .query(Timestamp.class)
                .single();

        assertThat(installedOn.toInstant()).isAfter(JVM_START);
    }

    @Test
    void healthEndpointReportsUp() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/actuator/health"))
                .GET()
                .build();

        HttpResponse<String> response;
        try (HttpClient client = HttpClient.newHttpClient()) {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        }

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }
}
