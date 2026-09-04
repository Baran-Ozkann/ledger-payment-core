package com.baran.ledger.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.baran.ledger.service.IdempotentOutcome;

/** The stored body goes back verbatim, so a replay is byte for byte the original response. */
final class IdempotentResponse {

    private IdempotentResponse() {
    }

    static ResponseEntity<String> of(IdempotentOutcome outcome) {
        return ResponseEntity.status(outcome.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(outcome.body());
    }
}
