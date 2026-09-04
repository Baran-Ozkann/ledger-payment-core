package com.baran.ledger.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.baran.ledger.config.RequestHashFilter;
import com.baran.ledger.domain.IdempotencyRequest;
import com.baran.ledger.domain.LedgerTransaction;
import com.baran.ledger.domain.Money;
import com.baran.ledger.service.LedgerService;

/**
 * Money enters the ledger here: an EQUITY account is debited so a LIABILITY account can be
 * credited, which keeps the funding transaction balanced like any other.
 */
@RestController
@RequestMapping("/v1/funding")
class FundingController {

    private static final Logger LOG = LoggerFactory.getLogger(FundingController.class);

    private final LedgerService ledger;

    FundingController(LedgerService ledger) {
        this.ledger = ledger;
    }

    @PostMapping
    ResponseEntity<String> fund(
            @RequestHeader(name = "X-Client-Id", required = false) String clientId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestAttribute(RequestHashFilter.REQUEST_HASH) String requestHash,
            @RequestBody TransferRequest request) {
        IdempotencyRequest idempotency = IdempotencyRequest.of(clientId, idempotencyKey, requestHash);
        LOG.debug("Funding requested by client {}", idempotency.clientId());
        return IdempotentResponse.of(ledger.fund(
                idempotency,
                request.fromAccount(),
                request.toAccount(),
                Money.of(request.amount()),
                request.description(),
                this::render));
    }

    private TransferResponse render(LedgerTransaction transaction) {
        return TransferResponse.of(transaction, ledger.entriesOfTransaction(transaction.publicId()));
    }
}
