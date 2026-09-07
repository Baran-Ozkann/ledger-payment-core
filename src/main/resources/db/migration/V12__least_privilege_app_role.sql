-- I5 is specified with two defenses: a trigger that RAISEs, and DB role grants. Only the first
-- existed. The application connected as the role the postgres image creates from POSTGRES_USER,
-- which that image makes a superuser, so it held UPDATE, DELETE and TRUNCATE on ledger_entries and
-- could switch every trigger off in one statement:
--
--     ALTER TABLE ledger_entries DISABLE TRIGGER USER;   -- I1, I5, I7 and I8, gone
--
-- A superuser is not needed to run this system, and an application that cannot be talked out of
-- append-only is worth more than one that merely promises it. ledger_app owns nothing and is
-- granted exactly the verbs the code issues. Disabling a trigger needs table ownership, which it
-- does not have; the revoke below is what makes the trigger unreachable rather than merely rude.

-- Created here rather than only in the compose init script so the role exists wherever the
-- migrations run, Testcontainers included. The password matters no more than every other
-- credential in this local-only project, all of which are in the repository by design.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ledger_app') THEN
        CREATE ROLE ledger_app LOGIN PASSWORD 'ledger_app';
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO ledger_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO ledger_app;

-- Append-only, and now enforced by the grant rather than only by the trigger. No UPDATE, no
-- DELETE, no TRUNCATE: a correction is a compensating entry, which is an INSERT.
GRANT SELECT, INSERT ON ledger_entries       TO ledger_app;
GRANT SELECT, INSERT ON ledger_transactions  TO ledger_app;

-- The materialized balance is the one thing in the ledger that legitimately changes in place, and
-- the conditional UPDATE in AccountRepository.debit is why. Accounts are never deleted.
GRANT SELECT, INSERT, UPDATE ON accounts     TO ledger_app;

-- The relay marks rows published and the archival job removes old ones, so these two carry the
-- verbs the ledger tables are denied. They are delivery bookkeeping, not history.
GRANT SELECT, INSERT, UPDATE, DELETE ON outbox_events     TO ledger_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON idempotency_keys  TO ledger_app;

-- The projection is derived and disposable; it is rebuilt by replaying events and is never what an
-- invariant is checked against. consumed_events is the dedup ledger and nothing prunes it yet.
GRANT SELECT, INSERT, UPDATE ON account_activity TO ledger_app;
GRANT SELECT, INSERT ON consumed_events          TO ledger_app;

-- Flyway's own table: readable so a running application can report its schema version, never
-- writable, because the application is not what migrates the database any more.
GRANT SELECT ON flyway_schema_history TO ledger_app;

-- Nothing is granted by default to a role created later, so future tables are closed until a
-- migration opens them deliberately. Stated rather than assumed, because the failure mode of
-- getting this wrong is silent.
ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE ALL ON TABLES FROM ledger_app;
