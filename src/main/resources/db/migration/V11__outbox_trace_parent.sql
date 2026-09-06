-- The relay publishes long after the request that wrote the row has answered and its trace has
-- ended. Without the context travelling in the row, the producer span starts a trace of its own and
-- the consumer joins that one, leaving no way to get from a slow request to the event it caused.
--
-- W3C traceparent, as text, because it is the format both ends already speak. Nullable on purpose:
-- an event written outside a trace has no context to carry, and inventing one would be a lie about
-- where it came from.

ALTER TABLE outbox_events ADD COLUMN trace_parent TEXT;
