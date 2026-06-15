package com.eventreliability.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The lifecycle record for a single failed message — the value stored (as JSON) in the compacted
 * {@code reliability.state} topic, keyed by {@code x-correlation-id} (§6, §9). Because the topic is
 * compacted, the latest record per correlation id survives and the topic <em>is</em> the current
 * system of record.
 *
 * <p>The opaque payload bytes are retained here (base64) together with the original headers so the
 * platform can faithfully re-drive the message on replay/retry without an external store (§10, §13).
 * Per-transition history is NOT kept here — it lives in the append-only audit log (§7.1, §8).
 *
 * <p>Immutable; use {@link #toBuilder()} to derive the next state.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FailureRecord(
        String correlationId,
        MessageState state,
        FailureClassification classification,
        RecommendedAction recommendedAction,

        String originalTopic,
        /** Which DLQ topic this failure arrived on (multi-team: each team/domain can have its own). */
        String dlqTopic,
        Integer originalPartition,
        Long originalOffset,

        String exceptionClass,
        String exceptionMessage,
        String stacktrace,

        int attemptCount,
        Long firstFailedAt,
        String sourceApp,
        Long eligibleAt,
        String currentTier,

        String schemaVersion,
        String payloadHash,
        String rootCauseSignature,

        /** Human-readable reason for a terminal routing/parking decision. */
        String reason,
        /** Last error seen while attempting a replay/retry, if any. */
        String lastError,
        /** The acting user for the most recent control action, if any. */
        String lastActor,

        /** Opaque payload body, base64-encoded, retained for faithful replay. */
        String payloadBase64,
        /** Original inbound headers, retained for faithful replay and audit. */
        Map<String, String> headers,

        Long createdAt,
        Long updatedAt) {

    public FailureRecord {
        headers = headers == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
    }

    public boolean isTerminal() {
        return state != null && state.isTerminal();
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Mutable builder used to derive successive immutable {@link FailureRecord} snapshots. */
    public static final class Builder {
        private String correlationId;
        private MessageState state;
        private FailureClassification classification;
        private RecommendedAction recommendedAction;
        private String originalTopic;
        private String dlqTopic;
        private Integer originalPartition;
        private Long originalOffset;
        private String exceptionClass;
        private String exceptionMessage;
        private String stacktrace;
        private int attemptCount;
        private Long firstFailedAt;
        private String sourceApp;
        private Long eligibleAt;
        private String currentTier;
        private String schemaVersion;
        private String payloadHash;
        private String rootCauseSignature;
        private String reason;
        private String lastError;
        private String lastActor;
        private String payloadBase64;
        private Map<String, String> headers = new LinkedHashMap<>();
        private Long createdAt;
        private Long updatedAt;

        private Builder() {
        }

        private Builder(FailureRecord r) {
            this.correlationId = r.correlationId;
            this.state = r.state;
            this.classification = r.classification;
            this.recommendedAction = r.recommendedAction;
            this.originalTopic = r.originalTopic;
            this.dlqTopic = r.dlqTopic;
            this.originalPartition = r.originalPartition;
            this.originalOffset = r.originalOffset;
            this.exceptionClass = r.exceptionClass;
            this.exceptionMessage = r.exceptionMessage;
            this.stacktrace = r.stacktrace;
            this.attemptCount = r.attemptCount;
            this.firstFailedAt = r.firstFailedAt;
            this.sourceApp = r.sourceApp;
            this.eligibleAt = r.eligibleAt;
            this.currentTier = r.currentTier;
            this.schemaVersion = r.schemaVersion;
            this.payloadHash = r.payloadHash;
            this.rootCauseSignature = r.rootCauseSignature;
            this.reason = r.reason;
            this.lastError = r.lastError;
            this.lastActor = r.lastActor;
            this.payloadBase64 = r.payloadBase64;
            this.headers = new LinkedHashMap<>(r.headers);
            this.createdAt = r.createdAt;
            this.updatedAt = r.updatedAt;
        }

        public Builder correlationId(String v) { this.correlationId = v; return this; }
        public Builder state(MessageState v) { this.state = v; return this; }
        public Builder classification(FailureClassification v) { this.classification = v; return this; }
        public Builder recommendedAction(RecommendedAction v) { this.recommendedAction = v; return this; }
        public Builder originalTopic(String v) { this.originalTopic = v; return this; }
        public Builder dlqTopic(String v) { this.dlqTopic = v; return this; }
        public Builder originalPartition(Integer v) { this.originalPartition = v; return this; }
        public Builder originalOffset(Long v) { this.originalOffset = v; return this; }
        public Builder exceptionClass(String v) { this.exceptionClass = v; return this; }
        public Builder exceptionMessage(String v) { this.exceptionMessage = v; return this; }
        public Builder stacktrace(String v) { this.stacktrace = v; return this; }
        public Builder attemptCount(int v) { this.attemptCount = v; return this; }
        public Builder firstFailedAt(Long v) { this.firstFailedAt = v; return this; }
        public Builder sourceApp(String v) { this.sourceApp = v; return this; }
        public Builder eligibleAt(Long v) { this.eligibleAt = v; return this; }
        public Builder currentTier(String v) { this.currentTier = v; return this; }
        public Builder schemaVersion(String v) { this.schemaVersion = v; return this; }
        public Builder payloadHash(String v) { this.payloadHash = v; return this; }
        public Builder rootCauseSignature(String v) { this.rootCauseSignature = v; return this; }
        public Builder reason(String v) { this.reason = v; return this; }
        public Builder lastError(String v) { this.lastError = v; return this; }
        public Builder lastActor(String v) { this.lastActor = v; return this; }
        public Builder payloadBase64(String v) { this.payloadBase64 = v; return this; }
        public Builder headers(Map<String, String> v) { this.headers = v == null ? new LinkedHashMap<>() : new LinkedHashMap<>(v); return this; }
        public Builder createdAt(Long v) { this.createdAt = v; return this; }
        public Builder updatedAt(Long v) { this.updatedAt = v; return this; }

        public FailureRecord build() {
            return new FailureRecord(
                    correlationId, state, classification, recommendedAction,
                    originalTopic, dlqTopic, originalPartition, originalOffset,
                    exceptionClass, exceptionMessage, stacktrace,
                    attemptCount, firstFailedAt, sourceApp, eligibleAt, currentTier,
                    schemaVersion, payloadHash, rootCauseSignature,
                    reason, lastError, lastActor,
                    payloadBase64, headers, createdAt, updatedAt);
        }
    }
}
