-- The ledger write and the event announcing it commit together, because the event is a row in the
-- same transaction. Publishing to a broker inside the transfer would do the opposite of what it
-- looks like: it announces transfers that then roll back, and loses the ones that commit.
--
-- published_at is the marker the relay filters on, and the reason there is no cursor here. A
-- high-water-mark (id > last_seen) is unsound: a BIGSERIAL value is handed out before commit, so a
-- row with a lower id can become visible after the cursor has moved past it, and is then skipped
-- forever. A NULL marker cannot be outrun.

CREATE TABLE outbox_events (
    id             BIGSERIAL   PRIMARY KEY,
    aggregate_type TEXT        NOT NULL,
    aggregate_id   TEXT        NOT NULL,
    event_type     TEXT        NOT NULL,
    payload        JSONB       NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ,
    attempts       INT         NOT NULL DEFAULT 0,
    last_error     TEXT
);

-- Partial on purpose: the relay only ever asks for unpublished rows, so the index it scans stays
-- the size of the backlog rather than the size of the table.
CREATE INDEX idx_outbox_unpublished ON outbox_events(id) WHERE published_at IS NULL;

-- Delivery is at-least-once: a crash between publishing and marking republishes the event. This
-- table is what makes the second delivery a no-op. The insert and the projection write share one
-- transaction, so an event is recorded as consumed exactly when its effect is durable.
CREATE TABLE consumed_events (
    consumer_group TEXT   NOT NULL,
    event_id       BIGINT NOT NULL,
    PRIMARY KEY (consumer_group, event_id)
);

-- A read model, keyed by the account's public id because that is all an event carries. Derived and
-- disposable: it is rebuilt by replaying events, and it is never what an invariant is checked
-- against. Only ledger_entries can answer that.
CREATE TABLE account_activity (
    account_id  UUID   PRIMARY KEY,
    entry_count BIGINT NOT NULL,
    net_amount  BIGINT NOT NULL
);
