package com.eventreliability.domain;

/**
 * The action a classification recommends (§7.1, §13). Produced by the classifier and recorded on
 * the state record so the console can show "what the platform decided and why".
 */
public enum RecommendedAction {
    /** Enter the tiered automatic-retry lane (§10). */
    AUTO_RETRY,
    /** Route to the business-routing topic with a reason; no retry. */
    ROUTE_TO_OWNER,
    /** Quarantine to the park topic immediately. */
    QUARANTINE,
    /** Park for human review (UNKNOWN). */
    PARK_FOR_REVIEW
}
