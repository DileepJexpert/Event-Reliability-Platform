package com.eventreliability.query;

import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

import com.eventreliability.api.FailureMapper;
import com.eventreliability.api.dto.FacetsDto;
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

    public PageDto<FailureSummaryDto> list(MessageState status, String topic, String dlqTopic,
                                           String sourceApp, FailureClassification classification,
                                           int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);

        List<FailureRecord> filtered = readModels.allFailures().stream()
                .filter(r -> status == null || r.state() == status)
                .filter(r -> matches(topic, r.originalTopic()))
                .filter(r -> matches(dlqTopic, r.dlqTopic()))
                .filter(r -> matches(sourceApp, r.sourceApp()))
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

    /** Case-insensitive "contains" match; a blank needle matches everything (char-based filtering). */
    private static boolean matches(String needle, String value) {
        if (needle == null || needle.isBlank()) {
            return true;
        }
        return value != null && value.toLowerCase().contains(needle.toLowerCase().trim());
    }

    /** Distinct source topics / DLQ topics / source apps across all failures, for filter autocomplete. */
    public FacetsDto facets() {
        TreeSet<String> topics = new TreeSet<>();
        TreeSet<String> dlqTopics = new TreeSet<>();
        TreeSet<String> sourceApps = new TreeSet<>();
        for (FailureRecord r : readModels.allFailures()) {
            addIfPresent(topics, r.originalTopic());
            addIfPresent(dlqTopics, r.dlqTopic());
            addIfPresent(sourceApps, r.sourceApp());
        }
        return new FacetsDto(List.copyOf(topics), List.copyOf(dlqTopics), List.copyOf(sourceApps));
    }

    private static void addIfPresent(TreeSet<String> set, String value) {
        if (value != null && !value.isBlank()) {
            set.add(value);
        }
    }

    public FailureDetailDto detail(String correlationId) {
        FailureRecord record = readModels.failure(correlationId)
                .orElseThrow(() -> new NotFoundException("No failure found for correlation id " + correlationId));
        return FailureMapper.toDetail(record, readModels.auditTimeline(correlationId).events());
    }
}
