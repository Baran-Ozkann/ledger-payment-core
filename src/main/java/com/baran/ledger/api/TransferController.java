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
import org.springframework.web.bind.annotation.RestController;

import com.baran.ledger.config.RequestHashFilter;
import com.baran.ledger.domain.IdempotencyRequest;
import com.baran.ledger.domain.LedgerTransaction;
import com.baran.ledger.domain.Money;
import com.baran.ledger.service.LedgerService;

@RestController
@RequestMapping("/v1/transfers")
class TransferController {

    private static final Logger LOG = LoggerFactory.getLogger(TransferController.class);

    private final LedgerService ledger;

    TransferController(LedgerService ledger) {
        this.ledger = ledger;
    }

    @PostMapping
    ResponseEntity<String> transfer(
            @RequestHeader(name = "X-Client-Id", required = false) String clientId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestAttribute(RequestHashFilter.REQUEST_HASH) String requestHash,
            @RequestBody TransferRequest request) {
        IdempotencyRequest idempotency = IdempotencyRequest.of(clientId, idempotencyKey, requestHash);
        LOG.debug("Transfer requested by client {}", idempotency.clientId());
        return IdempotentResponse.of(ledger.transfer(
                idempotency,
                request.fromAccount(),
                request.toAccount(),
                Money.of(request.amount()),
                request.description(),
                this::render));
    }

    @GetMapping("/{publicId}")
    TransferResponse get(@PathVariable UUID publicId) {
        return TransferResponse.of(ledger.transaction(publicId), ledger.entriesOfTransaction(publicId));
    }

    private TransferResponse render(LedgerTransaction transaction) {
        return TransferResponse.of(transaction, ledger.entriesOfTransaction(transaction.publicId()));
    }
}
