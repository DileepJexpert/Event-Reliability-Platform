package com.eventreliability.retry;

import java.time.Duration;

import com.eventreliability.config.ReliabilityProperties;
import com.eventreliability.domain.FailureHeaders;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * The data-driven retry clock and pause/seek delay loop (§10, §18.1, §18.2).
 *
 * <p>Subscribes to every retry-tier topic. For each message it compares {@code now} with the
 * {@code x-eligible-at} stamped at scheduling time:
 * <ul>
 *   <li><b>not yet eligible</b> — it negatively acknowledges with a bounded sleep
 *       ({@code Acknowledgment.nack(Duration)}). The container pauses the partition and re-seeks,
 *       but keeps calling {@code poll()} so the consumer stays in the group; it never sleeps the
 *       poll thread and never breaches {@code max.poll.interval}. Long tier delays are reached by
 *       repeatedly pausing for at most {@code reliability.retry.max-pause}.</li>
 *   <li><b>eligible</b> — it re-drives the message to the source and acknowledges.</li>
 * </ul>
 *
 * <p>Timing is decided entirely by the partition-owning consumer from data on the record, so under N
 * instances exactly one instance makes exactly one decision per message — no {@code @Scheduled}
 * timer that would fire on every replica and cause racing retries.
 */
@Component
public class DelayedRetryListener {

    private static final Logger log = LoggerFactory.getLogger(DelayedRetryListener.class);

    private final RetryRedriveService redriveService;
    private final long maxPauseMillis;

    public DelayedRetryListener(RetryRedriveService redriveService, ReliabilityProperties props) {
        this.redriveService = redriveService;
        this.maxPauseMillis = Math.max(1, props.retry().maxPause().toMillis());
    }

    @KafkaListener(
            topics = "#{@topicNames.allRetryTopics()}",
            id = "retry",
            containerFactory = "manualAckListenerContainerFactory")
    public void onRetryDue(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
        long eligibleAt = FailureHeaders.getLong(record.headers(), FailureHeaders.ELIGIBLE_AT, 0L);
        long now = System.currentTimeMillis();

        if (now < eligibleAt) {
            long sleep = Math.min(eligibleAt - now, maxPauseMillis);
            log.debug("RECV <- topic={} key={} not yet eligible ({} ms left); pausing {} ms",
                    record.topic(), record.key(), eligibleAt - now, sleep);
            // Pause this partition and re-seek; the container keeps polling while paused (§18.1).
            ack.nack(Duration.ofMillis(Math.max(1, sleep)));
            return;
        }

        log.info("RECV <- topic={} key={} eligible -> re-driving", record.topic(), record.key());
        try {
            redriveService.redrive(record);
            ack.acknowledge();
        } catch (RuntimeException ex) {
            // Don't commit; pause briefly and let the record be redelivered for another attempt.
            log.error("Re-drive failed for {} on {}; will retry shortly", record.key(), record.topic(), ex);
            ack.nack(Duration.ofMillis(Math.min(5000, maxPauseMillis)));
        }
    }
}
