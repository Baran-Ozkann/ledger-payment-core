package com.baran.ledger.api;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

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
    @ResponseStatus(HttpStatus.CREATED)
    AccountResponse create(
            @RequestHeader(name = "X-Client-Id", required = false) String clientId,
            @RequestBody CreateAccountRequest request) {
        LOG.debug("Account creation requested by client {}", ClientId.require(clientId));
        return AccountResponse.of(
                ledger.createAccount(request.accountType(), request.ownerRef(), request.allowNegative()));
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
