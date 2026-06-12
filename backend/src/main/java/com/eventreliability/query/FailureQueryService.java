package com.eventreliability.query;

import java.util.Comparator;
import java.util.List;

import com.eventreliability.api.FailureMapper;
import com.eventreliability.api.dto.FailureDetailDto;
import com.eventreliability.api.dto.FailureSummaryDto;
import com.eventreliability.api.dto.PageDto;
import com.eventreliability.domain.FailureClassification;
import com.eventreliability.domain.FailureRecord;
import com.eventreliability.domain.MessageState;
import com.eventreliability.streams.ReadModels;

import org.springframework.stereotype.Service;

/**
 * Serves the console's read queries (§15) from the GlobalKTable read models. Filtering, sorting and
 * pagination run in-process against the full local copy — no inter-instance RPC (§9). Listings are
 * capped to keep a single query bounded.
 */
@Service
public class FailureQueryService {

    private static final int MAX_PAGE_SIZE = 500;

    private final ReadModels readModels;

    public FailureQueryService(ReadModels readModels) {
        this.readModels = readModels;
    }

    public PageDto<FailureSummaryDto> list(MessageState status, String topic,
                                           FailureClassification classification, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);

        List<FailureRecord> filtered = readModels.allFailures().stream()
                .filter(r -> status == null || r.state() == status)
                .filter(r -> topic == null || topic.isBlank() || topic.equals(r.originalTopic()))
                .filter(r -> classification == null || r.classification() == classification)
                .sorted(Comparator.comparingLong((FailureRecord r) ->
                        r.updatedAt() == null ? 0L : r.updatedAt()).reversed())
                .toList();

        long total = filtered.size();
        List<FailureSummaryDto> pageContent = filtered.stream()
                .skip((long) safePage * safeSize)
                .limit(safeSize)
                .map(FailureMapper::toSummary)
                .toList();

        return PageDto.of(pageContent, safePage, safeSize, total);
    }

    public FailureDetailDto detail(String correlationId) {
        FailureRecord record = readModels.failure(correlationId)
                .orElseThrow(() -> new NotFoundException("No failure found for correlation id " + correlationId));
        return FailureMapper.toDetail(record, readModels.auditTimeline(correlationId).events());
    }
}
