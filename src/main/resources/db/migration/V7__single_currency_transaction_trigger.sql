-- I8: every entry of one transaction carries the same currency. Deferred for the same reason as
-- I1: a transaction holds one entry in between its own inserts, and one entry is never mixed.
--
-- I1 and I7 do not cover this. Entries of -1000 TRY and +1000 USD sum to zero and each one
-- matches the currency of its own account, so both pass while the transaction invents money.

CREATE OR REPLACE FUNCTION assert_tx_single_currency() RETURNS TRIGGER AS $$
DECLARE n INT;
BEGIN
    SELECT COUNT(DISTINCT currency) INTO n
    FROM ledger_entries WHERE transaction_id = NEW.transaction_id;
    IF n > 1 THEN
        RAISE EXCEPTION 'mixed currencies in transaction %: % distinct', NEW.transaction_id, n;
    END IF;
    RETURN NULL;
END $$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_tx_single_currency
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_tx_single_currency();
