package com.eventreliability.api;

import java.util.List;

import com.eventreliability.api.dto.FailureDetailDto;
import com.eventreliability.api.dto.FailureSummaryDto;
import com.eventreliability.domain.AuditEvent;
import com.eventreliability.domain.FailureRecord;

/** Maps domain {@link FailureRecord}s to the API DTOs. */
public final class FailureMapper {

    private FailureMapper() {
    }

    public static FailureSummaryDto toSummary(FailureRecord r, String owningTeam) {
        return new FailureSummaryDto(
                r.correlationId(), r.state(), r.classification(), r.recommendedAction(),
                r.originalTopic(), r.dlqTopic(), r.sourceApp(), r.exceptionClass(), r.exceptionMessage(),
                r.attemptCount(), r.currentTier(), r.rootCauseSignature(), r.reason(),
                r.firstFailedAt(), r.updatedAt(), owningTeam);
    }

    public static FailureDetailDto toDetail(FailureRecord r, List<AuditEvent> auditTimeline,
                                            String owningTeam, String maskedPayloadBase64) {
        return new FailureDetailDto(
                r.correlationId(), r.state(), r.classification(), r.recommendedAction(),
                r.originalTopic(), r.dlqTopic(), r.originalPartition(), r.originalOffset(), r.sourceApp(),
                r.exceptionClass(), r.exceptionMessage(), r.stacktrace(), r.attemptCount(),
                r.currentTier(), r.eligibleAt(), r.schemaVersion(), r.payloadHash(),
                r.rootCauseSignature(), r.reason(), r.lastError(), r.lastActor(),
                maskedPayloadBase64, r.headers(), r.firstFailedAt(), r.createdAt(), r.updatedAt(),
                owningTeam, auditTimeline);
    }
}
