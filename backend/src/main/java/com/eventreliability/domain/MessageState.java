package com.eventreliability.domain;

import java.util.Set;

/**
 * Message lifecycle states (§6.2):
 *
 * <pre>
 *   RECEIVED -&gt; CLASSIFIED -&gt; (RETRY_SCHEDULED -&gt; RETRYING)* -&gt; terminal
 * </pre>
 *
 * Terminal states are {@link #RESOLVED}, {@link #EXHAUSTED_PARKED}, {@link #ROUTED_BUSINESS},
 * {@link #QUARANTINED_POISON} and {@link #PARKED_UNKNOWN}. The remaining values are the
 * control-plane transitions (§6.2, §13).
 */
public enum MessageState {

    RECEIVED,
    CLASSIFIED,
    RETRY_SCHEDULED,
    RETRYING,

    // ---- terminal outcomes ----
    /** A retry succeeded (the message did not come back). */
    RESOLVED(true),
    /** All retry tiers exhausted; parked for human review. */
    EXHAUSTED_PARKED(true),
    /** Business failure handed to its owner with a reason; no retry. */
    ROUTED_BUSINESS(true),
    /** Poison message quarantined immediately; no retry. */
    QUARANTINED_POISON(true),
    /** UNKNOWN classification parked for human review; no blind retry. */
    PARKED_UNKNOWN(true),

    // ---- control-plane transitions (§13) ----
    REPLAY_REQUESTED,
    REPLAY_APPROVED,
    BULK_REPLAY_REQUESTED,
    BULK_REPLAY_APPROVED,
    /** A replay/bulk-replay was dispatched back to the source topic. */
    REPLAYED;

    private final boolean terminal;

    MessageState() {
        this(false);
    }

    MessageState(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean isTerminal() {
        return terminal;
    }

    /** The terminal states that physically live on the {@code reliability.parked} topic. */
    public static final Set<MessageState> PARKED_STATES =
            Set.of(EXHAUSTED_PARKED, PARKED_UNKNOWN, QUARANTINED_POISON);
}
