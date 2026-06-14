package com.eventreliability.retry;

import java.time.Instant;
import java.util.List;

import com.eventreliability.audit.AuditService;
import com.eventreliability.common.KafkaPublisher;
import com.eventreliability.config.ReliabilityProperties;
import com.eventreliability.config.ReliabilityProperties.Retry.Tier;
import com.eventreliability.config.TopicNames;
import com.eventreliability.domain.FailureHeaders;
import com.eventreliability.domain.FailureRecord;
import com.eventreliability.domain.MessageState;
import com.eventreliability.observability.PlatformMetrics;
import com.eventreliability.routing.ParkingService;
import com.eventreliability.state.StateService;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Decides retry-tier escalation and schedules due retries onto the per-tier topics (§10). The
 * platform is the single brain counting attempts: the tier is chosen from {@code attemptCount}
 * (tier index = attemptCount − 1), so each successive failure of the same message climbs to the next
 * backoff tier, and once the tiers are exhausted the message is parked for human review.
 *
 * <p>Scheduling stamps {@code x-eligible-at} on the message and writes it to the tier topic; the
 * actual delay is enforced by the partition-owning consumer's pause/seek loop ({@link
 * DelayedRetryListener}) — not by any timer here — so exactly one instance makes exactly one timing
 * decision (§10, §18.2).
 */
@Service
public class RetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(RetryScheduler.class);

    private final List<Tier> tiers;
    private final TopicNames topics;
    private final KafkaPublisher publisher;
    private final StateService stateService;
    private final AuditService auditService;
    private final ParkingService parkingService;
    private final PlatformMetrics metrics;

    public RetryScheduler(ReliabilityProperties props, TopicNames topics, KafkaPublisher publisher,
                          StateService stateService, AuditService auditService, ParkingService parkingService,
                          PlatformMetrics metrics) {
        this.tiers = props.retry().tiers();
        this.topics = topics;
        this.publisher = publisher;
        this.stateService = stateService;
        this.auditService = auditService;
        this.parkingService = parkingService;
        this.metrics = metrics;
    }

    /**
     * Schedule the next retry for a retryable failure, or park it if no tier remains. The tier is
     * derived from the message's attempt count.
     */
    public void schedule(FailureRecord record, byte[] payload, Headers original) {
        int attempt = Math.max(1, record.attemptCount());
        int tierIndex = attempt - 1;

        if (tierIndex >= tiers.size()) {
            parkingService.parkExhausted(record, payload, original,
                    "all " + tiers.size() + " retry tiers exhausted after " + attempt + " attempts");
            return;
        }

        Tier tier = tiers.get(tierIndex);
        long eligibleAt = System.currentTimeMillis() + tier.delay().toMillis();

        Headers h = FailureHeaders.copy(original);
        FailureHeaders.put(h, FailureHeaders.ELIGIBLE_AT, eligibleAt);
        FailureHeaders.put(h, FailureHeaders.RETRY_TIER, tier.name());
        FailureHeaders.put(h, FailureHeaders.ATTEMPT_COUNT, Integer.toString(attempt));
        if (record.classification() != null) {
            FailureHeaders.put(h, FailureHeaders.CLASSIFICATION, record.classification().name());
        }
        publisher.send(new ProducerRecord<>(topics.retry(tier.name()), null, record.correlationId(), payload, h));

        FailureRecord updated = record.toBuilder()
                .state(MessageState.RETRY_SCHEDULED)
                .currentTier(tier.name())
                .eligibleAt(eligibleAt)
                .build();
        stateService.put(updated);
        metrics.retryScheduled();
        auditService.system(record.correlationId(), record.state(), MessageState.RETRY_SCHEDULED,
                "RETRY_SCHEDULED",
                "tier " + tier.name() + " (attempt " + attempt + "/" + tiers.size()
                        + "), eligible at " + Instant.ofEpochMilli(eligibleAt));
        log.info("Scheduled retry for {} on tier {} -> topic {} eligible at {}",
                record.correlationId(), tier.name(), topics.retry(tier.name()),
                Instant.ofEpochMilli(eligibleAt));
    }

    public List<Tier> tiers() {
        return tiers;
    }
}
