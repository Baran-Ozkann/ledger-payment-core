-- I4: only an EQUITY account may go negative. allow_negative used to be a settable column, so a
-- LIABILITY account created with allow_negative = true could be overdrawn while every invariant
-- still held. It is now generated from account_type and no INSERT can override it.
--
-- PostgreSQL 16 cannot turn an existing column into a generated one, so the column is dropped and
-- re-added. Nothing is lost: the value was already meant to be a function of account_type, and it
-- is recomputed for every existing row.

ALTER TABLE accounts DROP CONSTRAINT balance_sign;
ALTER TABLE accounts DROP COLUMN allow_negative;

ALTER TABLE accounts ADD COLUMN allow_negative BOOLEAN NOT NULL
    GENERATED ALWAYS AS (account_type = 'EQUITY') STORED;

ALTER TABLE accounts ADD CONSTRAINT balance_sign CHECK (allow_negative OR balance >= 0);
