package com.baran.ledger.api;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.baran.ledger.service.IdempotentOutcome;

/** Status and body both come from the store, so a replay is the original response, not a note. */
final class IdempotentResponse {

    private IdempotentResponse() {
    }

    static ResponseEntity<String> of(IdempotentOutcome outcome) {
        return ResponseEntity.status(outcome.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(outcome.body());
    }
}
