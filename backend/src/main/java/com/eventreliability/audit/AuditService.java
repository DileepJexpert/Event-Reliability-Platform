package com.eventreliability.audit;

import java.util.Map;

import com.eventreliability.common.KafkaPublisher;
import com.eventreliability.config.TopicNames;
import com.eventreliability.domain.AuditEvent;
import com.eventreliability.domain.MessageState;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Appends to the immutable, append-only audit log (§7.1 step 5, §8). Every lifecycle transition and
 * every human/control action goes through here so the platform keeps a complete, tamper-evident
 * record of "what failed and how it was resolved" — the fourth pain the platform retires (§2).
 *
 * <p>Records are keyed by correlation id so a message's full timeline can be reconstructed; the
 * topic itself has compaction OFF and long retention, so nothing is ever overwritten.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final KafkaPublisher publisher;
    private final TopicNames topics;

    public AuditService(KafkaPublisher publisher, TopicNames topics) {
        this.publisher = publisher;
        this.topics = topics;
    }

    public void record(AuditEvent event) {
        publisher.sendJson(topics.audit(), event.correlationId(), event);
        if (log.isDebugEnabled()) {
            log.debug("AUDIT {} {} -> {} by {} : {}", event.correlationId(), event.fromState(),
                    event.toState(), event.actor(), event.detail());
        }
    }

    /** Record an automatic, system-driven transition. */
    public void system(String correlationId, MessageState from, MessageState to, String action, String detail) {
        record(AuditEvent.system(correlationId, from, to, action, detail));
    }

    /** Record a human/control-plane action, attributing the acting user (§13, §17). */
    public void userAction(String correlationId, MessageState from, MessageState to, String action,
                           String actor, String detail) {
        record(AuditEvent.byUser(correlationId, from, to, action, actor, detail));
    }

    /** Record an action with structured attributes (tier, signature, incident id…). */
    public void record(String correlationId, MessageState from, MessageState to, String action,
                       String actor, String detail, Map<String, String> attributes) {
        record(new AuditEvent(correlationId, System.currentTimeMillis(), from, to, action,
                actor == null ? AuditEvent.SYSTEM_ACTOR : actor, detail, attributes));
    }
}
