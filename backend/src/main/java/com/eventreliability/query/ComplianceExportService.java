package com.eventreliability.query;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import com.eventreliability.api.dto.ComplianceRow;
import com.eventreliability.domain.FailureRecord;
import com.eventreliability.ownership.OwnershipService;
import com.eventreliability.streams.ReadModels;

import org.springframework.stereotype.Service;

/**
 * Builds the compliance export (§17): a register of every failed transaction and its current
 * disposition over a date range, for auditors/regulators. Read-only and metadata-only — no payload
 * bytes are exported. Emitted as JSON ({@link ComplianceRow}) or CSV.
 */
@Service
public class ComplianceExportService {

    private static final String CSV_HEADER = "correlationId,firstFailedAt,state,classification,"
            + "owningTeam,originalTopic,dlqTopic,sourceApp,exceptionClass,exceptionMessage,"
            + "attemptCount,currentTier,lastActor,reason,updatedAt";

    private final ReadModels readModels;
    private final OwnershipService ownership;

    public ComplianceExportService(ReadModels readModels, OwnershipService ownership) {
        this.readModels = readModels;
        this.ownership = ownership;
    }

    /** Failures whose first-failed time falls in [{@code from}, {@code to}] (either bound optional). */
    public List<ComplianceRow> rows(Long from, Long to) {
        return readModels.allFailures().stream()
                .filter(r -> inRange(r.firstFailedAt(), from, to))
                .sorted(Comparator.comparingLong(r -> r.firstFailedAt() == null ? 0L : r.firstFailedAt()))
                .map(this::toRow)
                .toList();
    }

    public String toCsv(List<ComplianceRow> rows) {
        StringBuilder sb = new StringBuilder(CSV_HEADER).append('\n');
        for (ComplianceRow r : rows) {
            sb.append(csv(r.correlationId())).append(',')
                    .append(csv(r.firstFailedAt())).append(',')
                    .append(csv(r.state())).append(',')
                    .append(csv(r.classification())).append(',')
                    .append(csv(r.owningTeam())).append(',')
                    .append(csv(r.originalTopic())).append(',')
                    .append(csv(r.dlqTopic())).append(',')
                    .append(csv(r.sourceApp())).append(',')
                    .append(csv(r.exceptionClass())).append(',')
                    .append(csv(r.exceptionMessage())).append(',')
                    .append(r.attemptCount()).append(',')
                    .append(csv(r.currentTier())).append(',')
                    .append(csv(r.lastActor())).append(',')
                    .append(csv(r.reason())).append(',')
                    .append(csv(r.updatedAt())).append('\n');
        }
        return sb.toString();
    }

    private ComplianceRow toRow(FailureRecord r) {
        return new ComplianceRow(
                r.correlationId(), iso(r.firstFailedAt()),
                r.state() == null ? null : r.state().name(),
                r.classification() == null ? null : r.classification().name(),
                ownership.teamFor(r), r.originalTopic(), r.dlqTopic(), r.sourceApp(),
                r.exceptionClass(), r.exceptionMessage(), r.attemptCount(), r.currentTier(),
                r.lastActor(), r.reason(), iso(r.updatedAt()));
    }

    private static boolean inRange(Long ts, Long from, Long to) {
        if (from != null && (ts == null || ts < from)) {
            return false;
        }
        return to == null || (ts != null && ts <= to);
    }

    private static String iso(Long epochMillis) {
        return epochMillis == null ? "" : Instant.ofEpochMilli(epochMillis).toString();
    }

    /** RFC-4180-style CSV cell: quote when the value contains a comma, quote or newline. */
    private static String csv(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }
}
