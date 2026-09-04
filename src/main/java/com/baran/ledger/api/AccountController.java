package com.baran.ledger.api;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baran.ledger.config.RequestHashFilter;
import com.baran.ledger.domain.IdempotencyRequest;
import com.baran.ledger.service.LedgerService;

@RestController
@RequestMapping("/v1/accounts")
class AccountController {

    private static final Logger LOG = LoggerFactory.getLogger(AccountController.class);
    private static final int DEFAULT_PAGE_SIZE = 50;

    private final LedgerService ledger;

    AccountController(LedgerService ledger) {
        this.ledger = ledger;
    }

    @PostMapping
    ResponseEntity<String> create(
            @RequestHeader(name = "X-Client-Id", required = false) String clientId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestAttribute(RequestHashFilter.REQUEST_HASH) String requestHash,
            @RequestBody CreateAccountRequest request) {
        IdempotencyRequest idempotency = IdempotencyRequest.of(clientId, idempotencyKey, requestHash);
        LOG.debug("Account creation requested by client {}", idempotency.clientId());
        return IdempotentResponse.of(ledger.createAccount(
                idempotency, request.accountType(), request.ownerRef(), AccountResponse::of));
    }

    @GetMapping("/{publicId}")
    AccountResponse get(@PathVariable UUID publicId) {
        return AccountResponse.of(ledger.account(publicId));
    }

    @GetMapping("/{publicId}/entries")
    EntriesPage entries(
            @PathVariable UUID publicId,
            @RequestParam(required = false) Long after,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int limit) {
        return EntriesPage.of(ledger.entriesOfAccount(publicId, after, limit), limit);
    }
}
