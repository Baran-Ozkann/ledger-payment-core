-- Amounts are minor units (kurus) in BIGINT. The bound on ledger_entries.amount mirrors the
-- API-level maximum transfer so a single entry can never carry more than one transfer's worth.

CREATE TABLE accounts (
    id             BIGSERIAL   PRIMARY KEY,
    public_id      UUID        NOT NULL UNIQUE,
    account_type   TEXT        NOT NULL,
    owner_ref      TEXT,
    currency       CHAR(3)     NOT NULL,
    balance        BIGINT      NOT NULL DEFAULT 0,
    allow_negative BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT balance_sign CHECK (allow_negative OR balance >= 0),
    CONSTRAINT valid_account_type CHECK (
        account_type IN ('ASSET','LIABILITY','EQUITY','REVENUE','EXPENSE'))
);

CREATE TABLE ledger_transactions (
    id          BIGSERIAL   PRIMARY KEY,
    public_id   UUID        NOT NULL UNIQUE,
    tx_type     TEXT        NOT NULL,
    reverses_id BIGINT      REFERENCES ledger_transactions(id),
    description TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT valid_tx_type CHECK (
        tx_type IN ('TRANSFER','REVERSAL','FUNDING','FEE','ADJUSTMENT')),
    CONSTRAINT reversal_has_target CHECK (
        (tx_type = 'REVERSAL') = (reverses_id IS NOT NULL))
);

-- A transaction may be reversed at most once; without this a concurrent pair of reversals
-- would each pass an application-level check and both commit.
CREATE UNIQUE INDEX uq_single_reversal
    ON ledger_transactions(reverses_id) WHERE reverses_id IS NOT NULL;

CREATE TABLE ledger_entries (
    id             BIGSERIAL   PRIMARY KEY,
    transaction_id BIGINT      NOT NULL REFERENCES ledger_transactions(id),
    account_id     BIGINT      NOT NULL REFERENCES accounts(id),
    amount         BIGINT      NOT NULL,
    currency       CHAR(3)     NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT amount_nonzero CHECK (amount <> 0),
    CONSTRAINT amount_bounded CHECK (amount BETWEEN -10000000000 AND 10000000000)
);

CREATE INDEX idx_entries_account ON ledger_entries(account_id, id DESC);
CREATE INDEX idx_entries_tx      ON ledger_entries(transaction_id);
