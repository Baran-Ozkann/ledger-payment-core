package com.baran.ledger.domain;

/**
 * The transaction types this service can currently write. The valid_tx_type CHECK allows more;
 * each remaining type arrives with the phase that starts producing it.
 */
public enum TxType {
    TRANSFER,
    FUNDING
}
