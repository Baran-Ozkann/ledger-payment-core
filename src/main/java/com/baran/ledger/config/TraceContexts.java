package com.baran.ledger.config;

import java.util.HashMap;
import java.util.Map;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.stereotype.Component;

/**
 * Carries a trace across the outbox. The two halves happen threads and minutes apart: a request
 * writes the row inside its own trace, and the relay publishes it inside none.
 */
@Component
public class TraceContexts {

    private static final String TRACEPARENT = "traceparent";

    private final Tracer tracer;
    private final Propagator propagator;

    TraceContexts(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    /** @return the W3C traceparent of whatever is in flight, or null when nothing is */
    public String current() {
        Span span = tracer.currentSpan();
        if (span == null) {
            return null;
        }
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(span.context(), carrier, Map::put);
        return carrier.get(TRACEPARENT);
    }

    /**
     * Runs the work inside the trace the row was written in, so the producer span it creates is a
     * child of the request that caused the event rather than the root of something unrelated.
     *
     * <p>A row with no stored context runs as it is. Starting a fresh trace would be worse than
     * leaving it alone: it would read as an origin, when the origin is simply not recorded.
     */
    public void continuing(String traceParent, String spanName, Runnable work) {
        if (traceParent == null) {
            work.run();
            return;
        }

        Span span = propagator.extract(Map.of(TRACEPARENT, traceParent), Map::get).name(spanName).start();
        try (Tracer.SpanInScope active = tracer.withSpan(span)) {
            work.run();
        } finally {
            span.end();
        }
    }
}
