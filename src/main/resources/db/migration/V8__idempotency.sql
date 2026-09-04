-- I6: a (client_id, idem_key) pair maps to at most one transaction. The UNIQUE constraint is the
-- whole mechanism; the protocol above it only interprets whether the INSERT won the race.
--
-- The row is claimed IN_PROGRESS and completed in the same transaction as the ledger write, so a
-- caller that reads a response knows the key is durably taken and a rollback releases both.

CREATE TABLE idempotency_keys (
    id             BIGSERIAL   PRIMARY KEY,
    client_id      TEXT        NOT NULL,
    idem_key       TEXT        NOT NULL,
    request_hash   TEXT        NOT NULL,
    status         TEXT        NOT NULL,
    response_code  INT,
    response_body  JSONB,
    transaction_id BIGINT      REFERENCES ledger_transactions(id),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at     TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_client_key UNIQUE (client_id, idem_key),
    CONSTRAINT valid_status CHECK (status IN ('IN_PROGRESS','COMPLETED'))
);

CREATE INDEX idx_idem_expiry ON idempotency_keys(expires_at);
