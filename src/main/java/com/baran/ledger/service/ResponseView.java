package com.baran.ledger.service;

/**
 * Renders the response body for a completed operation. The API layer owns the wire format, but the
 * body has to be produced inside the ledger transaction so that it can be stored with it: a replay
 * then returns the original bytes rather than a fresh rendering of whatever the row looks like now.
 */
@FunctionalInterface
public interface ResponseView<T> {

    Object render(T result);
}
