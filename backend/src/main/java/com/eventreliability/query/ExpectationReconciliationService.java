package com.eventreliability.query;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.eventreliability.api.dto.ExpectationReconciliationDto;
import com.eventreliability.domain.Expectation;
import com.eventreliability.domain.FailureRecord;
import com.eventreliability.domain.MessageState;
import com.eventreliability.streams.ReadModels;

import org.springframework.stereotype.Service;

/**
 * Reconciles each declared {@link Expectation} against the failures Brod captured for its source: of the
 * declared {@code expectedCount}, how many are still un-recovered (the shortfall) versus presumed
 * complete. Read-only — answers "did the declared volume complete?" as far as Brod can see.
 *
 * <p>Note the visibility limit: Brod only sees failures, so it detects events stuck in the failure
 * pipeline, not events that never arrived at all. The declared count provides the denominator; full
 * events-in-vs-out would also need the producer to report actual completions.
 */
@Service
public class ExpectationReconciliationService {

    private static final Set<MessageState> COMPLETED = EnumSet.of(MessageState.RESOLVED, MessageState.REPLAYED);

    private final ExpectationStore store;
    private final ReadModels readModels;

    public ExpectationReconciliationService(ExpectationStore store, ReadModels readModels) {
        this.store = store;
        this.readModels = readModels;
    }

    public List<ExpectationReconciliationDto> reconcileAll() {
        List<FailureRecord> all = readModels.allFailures();
        return store.all().stream()
                .sorted(Comparator.comparingLong(Expectation::declaredAt).reversed())
                .map(e -> reconcileOne(e, all))
                .toList();
    }

    public ExpectationReconciliationDto reconcile(Expectation expectation) {
        return reconcileOne(expectation, readModels.allFailures());
    }

    private ExpectationReconciliationDto reconcileOne(Expectation e, List<FailureRecord> all) {
        long failed = 0;
        long recovered = 0;
        for (FailureRecord r : all) {
            if (!matches(e, r)) {
                continue;
            }
            failed++;
            if (r.state() != null && COMPLETED.contains(r.state())) {
                recovered++;
            }
        }
        long open = failed - recovered;
        long completed = Math.max(0, e.expectedCount() - open);
        double rate = e.expectedCount() > 0
                ? Math.min(1.0, completed / (double) e.expectedCount()) : 1.0;
        rate = Math.round(rate * 10000.0) / 10000.0;
        String status = open == 0 ? "RECONCILED" : "SHORTFALL";
        return new ExpectationReconciliationDto(e.key(), e.source(), e.label(), e.expectedCount(),
                failed, recovered, open, completed, rate, status,
                e.windowStartMs(), e.windowEndMs(), e.declaredAt(), e.declaredBy());
    }

    private static boolean matches(Expectation e, FailureRecord r) {
        if (e.source() != null && !e.source().equals(r.originalTopic())) {
            return false;
        }
        Long ff = r.firstFailedAt();
        if (e.windowStartMs() != null && (ff == null || ff < e.windowStartMs())) {
            return false;
        }
        if (e.windowEndMs() != null && (ff == null || ff > e.windowEndMs())) {
            return false;
        }
        return true;
    }
}
