package com.baran.ledger.schema;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * I5's second defense. CONVENTIONS.md specifies immutability as a trigger that RAISEs <em>and</em> DB
 * role grants, and until V12 only the trigger existed: the application connected as the cluster
 * superuser, which could switch every trigger off in one statement.
 *
 * <p>These assertions are about the role rather than about behaviour, because that is where the
 * defense lives. A trigger can be disabled by whoever owns the table; a privilege that was never
 * granted cannot be talked into existing by anything the application is able to send.
 */
class AppRolePrivilegesTest extends SchemaTestSupport {

    private static final String ROLE = "ledger_app";

    @Test
    void appRoleIsNeitherSuperuserNorAbleToCreateRoles() {
        assertThat(flag("rolsuper")).as("a superuser ignores every grant below").isFalse();
        assertThat(flag("rolcreaterole")).as("could grant itself anything it lacks").isFalse();
        assertThat(flag("rolcreatedb")).isFalse();
        assertThat(flag("rolbypassrls")).isFalse();
    }

    @Test
    void appRoleCannotRewriteOrRemoveLedgerHistory() {
        for (String table : new String[] {"ledger_entries", "ledger_transactions"}) {
            assertThat(can(table, "SELECT")).as("%s must stay readable", table).isTrue();
            assertThat(can(table, "INSERT")).as("%s must stay appendable", table).isTrue();
            assertThat(can(table, "UPDATE")).as("%s must not be rewritable", table).isFalse();
            assertThat(can(table, "DELETE")).as("%s must not be erasable", table).isFalse();
            assertThat(can(table, "TRUNCATE")).as("%s must not be truncatable", table).isFalse();
        }
    }

    /**
     * Disabling a trigger requires owning the table. This is what stops the first defense from
     * being removed by the same connection the second one constrains.
     */
    @Test
    void appRoleOwnsNoneOfTheLedgerTables() {
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM pg_tables
                        WHERE schemaname = 'public' AND tableowner = ?""")
                .param(ROLE)
                .query(Long.class)
                .single())
                .isZero();
    }

    /** The balance is the one thing that legitimately changes in place; the conditional debit needs it. */
    @Test
    void appRoleCanStillDoItsJob() {
        assertThat(can("accounts", "UPDATE")).isTrue();
        assertThat(can("accounts", "DELETE")).as("accounts are never deleted").isFalse();
        assertThat(can("outbox_events", "UPDATE")).as("the relay marks rows published").isTrue();
        assertThat(can("outbox_events", "DELETE")).as("the archival job prunes them").isTrue();
        assertThat(can("idempotency_keys", "DELETE")).as("the cleanup job prunes them").isTrue();
        assertThat(can("account_activity", "UPDATE")).as("the projection upserts").isTrue();
    }

    private boolean can(String table, String privilege) {
        return jdbc.sql("SELECT has_table_privilege(?, ?, ?)")
                .params(ROLE, table, privilege)
                .query(Boolean.class)
                .single();
    }

    private boolean flag(String column) {
        return jdbc.sql("SELECT " + column + " FROM pg_roles WHERE rolname = ?")
                .param(ROLE)
                .query(Boolean.class)
                .single();
    }
}
