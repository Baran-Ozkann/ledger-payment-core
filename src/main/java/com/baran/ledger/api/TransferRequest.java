package com.baran.ledger.api;

import java.util.UUID;

/** A missing amount deserializes to 0, which V1 rejects like any other non-positive amount. */
record TransferRequest(UUID fromAccount, UUID toAccount, long amount, String description) {
}
