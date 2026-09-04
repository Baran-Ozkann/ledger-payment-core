package com.baran.ledger.api;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.baran.ledger.domain.LedgerError;
import com.baran.ledger.domain.LedgerException;

/** RFC 7807 bodies. The error code is the problem type, so a client matches on one field. */
@RestControllerAdvice
class LedgerExceptionHandler {

    @ExceptionHandler(LedgerException.class)
    ProblemDetail handle(LedgerException exception) {
        LedgerError error = exception.error();
        ProblemDetail problem = ProblemDetail.forStatus(statusOf(error));
        problem.setType(URI.create("urn:ledger:" + error.code()));
        problem.setTitle(error.title());
        return problem;
    }

    private static HttpStatus statusOf(LedgerError error) {
        return switch (error) {
            case INVALID_AMOUNT, AMOUNT_TOO_LARGE, SELF_TRANSFER, CURRENCY_MISMATCH,
                 INSUFFICIENT_FUNDS, INVALID_FUNDING_ACCOUNTS -> HttpStatus.UNPROCESSABLE_CONTENT;
            case MISSING_CLIENT_ID, INVALID_PAGE_SIZE -> HttpStatus.BAD_REQUEST;
            case ACCOUNT_NOT_FOUND, TRANSACTION_NOT_FOUND -> HttpStatus.NOT_FOUND;
        };
    }
}
