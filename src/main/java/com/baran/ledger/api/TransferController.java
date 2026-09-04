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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

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
    @ResponseStatus(HttpStatus.CREATED)
    TransferResponse transfer(
            @RequestHeader(name = "X-Client-Id", required = false) String clientId,
            @RequestBody TransferRequest request) {
        LOG.debug("Transfer requested by client {}", ClientId.require(clientId));
        LedgerTransaction transaction = ledger.transfer(
                request.fromAccount(), request.toAccount(), Money.of(request.amount()), request.description());
        return TransferResponse.of(transaction, ledger.entriesOfTransaction(transaction.publicId()));
    }

    @GetMapping("/{publicId}")
    TransferResponse get(@PathVariable UUID publicId) {
        return TransferResponse.of(ledger.transaction(publicId), ledger.entriesOfTransaction(publicId));
    }
}
