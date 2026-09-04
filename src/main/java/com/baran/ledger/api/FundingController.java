package com.baran.ledger.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

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
    @ResponseStatus(HttpStatus.CREATED)
    TransferResponse fund(
            @RequestHeader(name = "X-Client-Id", required = false) String clientId,
            @RequestBody TransferRequest request) {
        LOG.debug("Funding requested by client {}", ClientId.require(clientId));
        LedgerTransaction transaction = ledger.fund(
                request.fromAccount(), request.toAccount(), Money.of(request.amount()), request.description());
        return TransferResponse.of(transaction, ledger.entriesOfTransaction(transaction.publicId()));
    }
}
