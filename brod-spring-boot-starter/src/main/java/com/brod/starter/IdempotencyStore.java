package com.brod.starter;

/**
 * Consumer-side dedup so retries and replays are safe (a re-delivered or re-driven message must not
 * re-run a side effect — the double-transaction risk). Call {@link #markProcessed} with the message's
 * idempotency key (typically the correlation/transaction id) before applying side effects, and skip the
 * message if it returns {@code false}.
 *
 * <p>The default {@link InMemoryIdempotencyStore} is process-local and unbounded — fine for tests and
 * single-instance use. For production, provide a bean backed by a shared store (Postgres, Redis,
 * Aerospike) so dedup holds across instances and restarts.
 */
public interface IdempotencyStore {

    /**
     * Atomically record {@code key} as processed.
     *
     * @return {@code true} if it was newly recorded (safe to process); {@code false} if already seen
     *         (a duplicate — skip the side effect).
     */
    boolean markProcessed(String key);

    /** Whether {@code key} has already been processed. */
    boolean isProcessed(String key);
}
