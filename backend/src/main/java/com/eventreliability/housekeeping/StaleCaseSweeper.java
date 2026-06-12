package com.eventreliability.housekeeping;

import com.eventreliability.audit.AuditService;
import com.eventreliability.config.ReliabilityProperties;
import com.eventreliability.domain.FailureRecord;
import com.eventreliability.domain.MessageState;
import com.eventreliability.observability.PlatformMetrics;
import com.eventreliability.state.StateService;
import com.eventreliability.streams.ReadModels;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Housekeeping sweeps (§9, §10). This is the <em>only</em> place {@code @Scheduled} is used, and
 * deliberately not for the retry clock (§18.2): housekeeping is idempotent, so it is harmless for
 * the timer to fire on every instance.
 *
 * <p>It does two things:
 * <ol>
 *   <li><b>Presumed-resolved promotion</b> — onboarded apps only adopt the failure-header contract,
 *       so the platform never receives a success callback. A message that was re-driven (RETRYING)
 *       and has not come back as a new failure within the resolve-grace is promoted to RESOLVED.</li>
 *   <li><b>Terminal TTL</b> — terminal-and-aged state records are tombstoned so compaction can
 *       forget closed cases (the compacted state topic has no built-in "forget").</li>
 * </ol>
 */
@Component
public class StaleCaseSweeper {

    private static final Logger log = LoggerFactory.getLogger(StaleCaseSweeper.class);

    private final ReadModels readModels;
    private final StateService stateService;
    private final AuditService auditService;
    private final ReliabilityProperties props;
    private final PlatformMetrics metrics;

    public StaleCaseSweeper(ReadModels readModels, StateService stateService,
                            AuditService auditService, ReliabilityProperties props, PlatformMetrics metrics) {
        this.readModels = readModels;
        this.stateService = stateService;
        this.auditService = auditService;
        this.props = props;
        this.metrics = metrics;
    }

    @Scheduled(
            fixedDelayString = "${reliability.housekeeping.stale-sweep-interval-ms:3600000}",
            initialDelayString = "${reliability.housekeeping.stale-sweep-interval-ms:3600000}")
    public void sweep() {
        if (!readModels.ready()) {
            return;
        }
        long now = System.currentTimeMillis();
        long resolveGrace = props.housekeeping().resolveGrace().toMillis();
        long terminalRetention = props.housekeeping().terminalRetention().toMillis();

        int resolved = 0;
        int forgotten = 0;
        for (FailureRecord r : readModels.allFailures()) {
            long updatedAt = r.updatedAt() == null ? 0L : r.updatedAt();
            if (r.state() == MessageState.RETRYING && now - updatedAt > resolveGrace) {
                stateService.put(r.toBuilder().state(MessageState.RESOLVED).build());
                auditService.system(r.correlationId(), MessageState.RETRYING, MessageState.RESOLVED,
                        "RESOLVED", "presumed resolved — no re-failure within grace after re-drive");
                metrics.resolved();
                resolved++;
            } else if (r.state().isTerminal() && now - updatedAt > terminalRetention) {
                stateService.forget(r.correlationId());
                auditService.system(r.correlationId(), r.state(), r.state(), "FORGOTTEN",
                        "terminal-and-aged — tombstoned per retention policy");
                forgotten++;
            }
        }
        if (resolved > 0 || forgotten > 0) {
            log.info("Housekeeping sweep: {} presumed-resolved, {} tombstoned", resolved, forgotten);
        }
    }
}
