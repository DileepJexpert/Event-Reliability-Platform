package com.eventreliability.query;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.eventreliability.api.dto.ReconciliationDto;
import com.eventreliability.api.dto.ReconciliationGroup;
import com.eventreliability.api.dto.ReconciliationItem;
import com.eventreliability.domain.FailureRecord;
import com.eventreliability.domain.MessageState;
import com.eventreliability.ownership.OwnershipService;
import com.eventreliability.streams.ReadModels;

import org.springframework.stereotype.Service;

/**
 * Reconciliation / completeness (§16): of every failure Brod captured, how many have reached
 * completion versus how many are still open (the reconciliation gap). A failure is <em>completed</em>
 * when it reached {@link MessageState#RESOLVED} (a retry succeeded) or {@link MessageState#REPLAYED}
 * (re-driven for reprocessing); everything else is open and must be settled. Grouped by source app and
 * topic, with the oldest open items as a worklist. Read-only, computed on demand from the read models.
 */
@Service
public class ReconciliationService {

    private static final int TOP_OPEN = 20;
    /** States in which a failed transaction is considered to have completed processing. */
    private static final Set<MessageState> COMPLETED = EnumSet.of(MessageState.RESOLVED, MessageState.REPLAYED);

    private final ReadModels readModels;
    private final OwnershipService ownership;

    public ReconciliationService(ReadModels readModels, OwnershipService ownership) {
        this.readModels = readModels;
        this.ownership = ownership;
    }

    public ReconciliationDto compute() {
        long total = 0;
        long completed = 0;
        long open = 0;
        Long oldestOpen = null;
        Map<String, Acc> bySource = new LinkedHashMap<>();
        Map<String, Acc> byTopic = new LinkedHashMap<>();
        List<ReconciliationItem> openItems = new ArrayList<>();

        for (FailureRecord r : readModels.allFailures()) {
            total++;
            boolean done = r.state() != null && COMPLETED.contains(r.state());
            String source = label(r.sourceApp());
            String topic = label(r.originalTopic());
            Acc s = bySource.computeIfAbsent(source, k -> new Acc());
            Acc t = byTopic.computeIfAbsent(topic, k -> new Acc());
            s.total++;
            t.total++;

            if (done) {
                completed++;
                s.completed++;
                t.completed++;
            } else {
                open++;
                s.open++;
                t.open++;
                Long ff = r.firstFailedAt();
                if (ff != null && (oldestOpen == null || ff < oldestOpen)) {
                    oldestOpen = ff;
                }
                s.touch(ff);
                t.touch(ff);
                openItems.add(new ReconciliationItem(r.correlationId(), topic, ownership.teamFor(r),
                        r.state() == null ? null : r.state().name(), ff));
            }
        }

        openItems.sort(Comparator.comparingLong(i -> i.firstFailedAt() == null ? Long.MAX_VALUE : i.firstFailedAt()));
        List<ReconciliationItem> oldest = openItems.size() > TOP_OPEN ? openItems.subList(0, TOP_OPEN) : openItems;

        return new ReconciliationDto(total, completed, open, rate(completed, total), oldestOpen,
                System.currentTimeMillis(), groups(bySource), groups(byTopic), List.copyOf(oldest));
    }

    private static List<ReconciliationGroup> groups(Map<String, Acc> src) {
        return src.entrySet().stream()
                .map(e -> new ReconciliationGroup(e.getKey(), e.getValue().total, e.getValue().completed,
                        e.getValue().open, rate(e.getValue().completed, e.getValue().total),
                        e.getValue().oldestOpenAt))
                .sorted(Comparator.comparingLong(ReconciliationGroup::open).reversed())
                .toList();
    }

    private static double rate(long completed, long total) {
        if (total == 0) {
            return 1.0;
        }
        return Math.round((completed / (double) total) * 10000.0) / 10000.0;
    }

    private static String label(String s) {
        return (s == null || s.isBlank()) ? "(unknown)" : s;
    }

    private static final class Acc {
        private long total;
        private long completed;
        private long open;
        private Long oldestOpenAt;

        void touch(Long ff) {
            if (ff != null && (oldestOpenAt == null || ff < oldestOpenAt)) {
                oldestOpenAt = ff;
            }
        }
    }
}
