-- I5: ledger records are append-only. The trigger RAISEs rather than swallowing the statement:
-- a rule with DO INSTEAD NOTHING would let the caller believe the write succeeded.

CREATE OR REPLACE FUNCTION reject_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'ledger records are immutable: % on %', TG_OP, TG_TABLE_NAME;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER trg_entries_immutable
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

CREATE TRIGGER trg_transactions_immutable
    BEFORE UPDATE OR DELETE ON ledger_transactions
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();
