-- I1: entries of a transaction sum to zero. The check must be deferred to commit because a
-- transaction is unbalanced in between its own inserts.

CREATE OR REPLACE FUNCTION assert_tx_balanced() RETURNS TRIGGER AS $$
DECLARE s BIGINT;
BEGIN
    SELECT COALESCE(SUM(amount), 0) INTO s
    FROM ledger_entries WHERE transaction_id = NEW.transaction_id;
    IF s <> 0 THEN
        RAISE EXCEPTION 'unbalanced transaction %: sum=%', NEW.transaction_id, s;
    END IF;
    RETURN NULL;
END $$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_tx_balanced
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_tx_balanced();
