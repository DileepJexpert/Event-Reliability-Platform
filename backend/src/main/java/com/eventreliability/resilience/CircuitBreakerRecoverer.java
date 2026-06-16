package com.eventreliability.resilience;

import com.eventreliability.config.ReliabilityProperties;
import com.eventreliability.observability.NotificationSender;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Component;

/**
 * Recoverer invoked when a platform listener exhausts its (short) retries on a record it cannot
 * process. It logs the failure, feeds the {@link ProcessingCircuitBreaker}, and — on the failure that
 * <em>opens</em> the breaker — pauses every listener container and raises a single CRITICAL alert.
 * Pausing stops the platform from silently skipping a flood of records while the systemic issue is
 * investigated; resume via {@code POST /api/admin/consumers/resume}.
 *
 * <p>The registry is taken as an {@link ObjectProvider} to avoid a bean wiring cycle (error handler →
 * recoverer → registry → containers → factory → error handler).
 */
@Component
public class CircuitBreakerRecoverer implements ConsumerRecordRecoverer {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerRecoverer.class);

    private final ProcessingCircuitBreaker breaker;
    private final ObjectProvider<KafkaListenerEndpointRegistry> registry;
    private final NotificationSender sender;
    private final ReliabilityProperties props;

    public CircuitBreakerRecoverer(ProcessingCircuitBreaker breaker,
                                   ObjectProvider<KafkaListenerEndpointRegistry> registry,
                                   NotificationSender sender, ReliabilityProperties props) {
        this.breaker = breaker;
        this.registry = registry;
        this.sender = sender;
        this.props = props;
    }

    @Override
    public void accept(ConsumerRecord<?, ?> record, Exception exception) {
        log.error("Platform failed to process record topic={} partition={} offset={}: {}",
                record.topic(), record.partition(), record.offset(), exception.toString(), exception);
        if (breaker.onFailure()) {
            int paused = pauseAll();
            String summary = String.format(
                    "CRITICAL: Event Reliability Platform paused %d consumer(s) after repeated processing "
                            + "failures (last: %s on %s-%d@%d). Investigate, then resume via "
                            + "POST /api/admin/consumers/resume.",
                    paused, exception.getClass().getSimpleName(), record.topic(), record.partition(),
                    record.offset());
            log.error(summary);
            alert(summary);
        }
    }

    private int pauseAll() {
        KafkaListenerEndpointRegistry reg = registry.getIfAvailable();
        if (reg == null) {
            return 0;
        }
        int count = 0;
        for (MessageListenerContainer container : reg.getListenerContainers()) {
            if (container.isRunning() && !container.isContainerPaused()) {
                container.pause();
                count++;
            }
        }
        return count;
    }

    private void alert(String summary) {
        ReliabilityProperties.Notifier cfg = props.notifier();
        if (!cfg.enabled()) {
            return; // already logged at ERROR; nothing wired to deliver to
        }
        boolean webhook = "webhook".equalsIgnoreCase(cfg.channel())
                && cfg.webhookUrl() != null && !cfg.webhookUrl().isBlank();
        String channel = webhook ? cfg.webhookUrl() : null;
        sender.send(new NotificationSender.Notification("PLATFORM", channel, summary, null));
    }
}
