package com.baran.ledger.store;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AccountActivityRepository {

    private final JdbcClient jdbc;

    AccountActivityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * An accumulation, which is exactly why the consumer may not apply an event twice. Nothing in
     * this statement can tell a repeat from a new entry; that is decided one table over, in the
     * same transaction as this write.
     */
    public void apply(UUID accountPublicId, long amount) {
        jdbc.sql("""
                        INSERT INTO account_activity (account_id, entry_count, net_amount)
                        VALUES (?, 1, ?)
                        ON CONFLICT (account_id) DO UPDATE
                        SET entry_count = account_activity.entry_count + 1,
                            net_amount  = account_activity.net_amount + EXCLUDED.net_amount""")
                .params(accountPublicId, amount)
                .update();
    }
}
