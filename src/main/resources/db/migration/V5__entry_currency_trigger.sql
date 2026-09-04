-- I7: an entry carries its own currency for auditability, so it has to be checked against the
-- account it posts to. BEFORE INSERT, so a mismatched row never reaches the table.

CREATE OR REPLACE FUNCTION assert_entry_currency() RETURNS TRIGGER AS $$
DECLARE acct_ccy CHAR(3);
BEGIN
    SELECT currency INTO acct_ccy FROM accounts WHERE id = NEW.account_id;
    IF NEW.currency <> acct_ccy THEN
        RAISE EXCEPTION 'entry currency % does not match account % currency %',
            NEW.currency, NEW.account_id, acct_ccy;
    END IF;
    RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER trg_entry_currency
    BEFORE INSERT ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION assert_entry_currency();
