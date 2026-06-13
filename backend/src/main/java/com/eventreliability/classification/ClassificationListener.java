package com.eventreliability.classification;

import com.eventreliability.audit.AuditService;
import com.eventreliability.domain.FailureHeaders;
import com.eventreliability.domain.FailureRecord;
import com.eventreliability.domain.MessageState;
import com.eventreliability.ingestion.FailureRecordFactory;
import com.eventreliability.observability.PlatformMetrics;
import com.eventreliability.routing.RoutingService;
import com.eventreliability.state.StateService;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Asynchronous classification + routing (§7.1 steps 2–3, §11). Consumes the internal classification
 * topic that ingestion forwards to, classifies from the header context, advances the message to
 * {@link MessageState#CLASSIFIED} with the chosen taxonomy/action/reason, audits the decision and
 * hands the message to {@link RoutingService}.
 *
 * <p>Reconstructing the record from the message headers (rather than depending on the just-written
 * state being visible in the GlobalKTable) keeps this stage correct under the read model's eventual
 * consistency; the prior created-at is preserved when the state record is already visible.
 */
@Component
public class ClassificationListener {

    private static final Logger log = LoggerFactory.getLogger(ClassificationListener.class);

    private final FailureRecordFactory recordFactory;
    private final Classifier classifier;
    private final StateService stateService;
    private final AuditService auditService;
    private final RoutingService routingService;
    private final PlatformMetrics metrics;

    public ClassificationListener(FailureRecordFactory recordFactory, Classifier classifier,
                                  StateService stateService, AuditService auditService,
                                  RoutingService routingService, PlatformMetrics metrics) {
        this.recordFactory = recordFactory;
        this.classifier = classifier;
        this.stateService = stateService;
        this.auditService = auditService;
        this.routingService = routingService;
        this.metrics = metrics;
    }

    @KafkaListener(topics = "#{@topicNames.classify()}", id = "classification")
    public void onClassify(ConsumerRecord<String, byte[]> record) {
        Headers h = record.headers();
        log.info("RECV <- topic={} key={} partition={} offset={}", record.topic(), record.key(),
                record.partition(), record.offset());
        String correlationId = record.key() != null
                ? record.key()
                : FailureHeaders.getString(h, FailureHeaders.CORRELATION_ID);
        if (correlationId == null || correlationId.isBlank()) {
            log.warn("Dropping classification message with no correlation id");
            return;
        }

        FailureRecord existing = stateService.find(correlationId).orElse(null);

        ClassificationResult result = classifier.classify(
                FailureHeaders.getString(h, FailureHeaders.EXCEPTION_CLASS),
                FailureHeaders.getString(h, FailureHeaders.EXCEPTION_MESSAGE));

        FailureRecord.Builder b = recordFactory.fromMessage(correlationId, h, record.value())
                .state(MessageState.CLASSIFIED)
                .classification(result.classification())
                .recommendedAction(result.action())
                .reason(result.reason());
        if (existing != null && existing.createdAt() != null) {
            b.createdAt(existing.createdAt());
        }
        FailureRecord classified = b.build();

        stateService.put(classified);
        auditService.system(correlationId,
                existing == null ? MessageState.RECEIVED : existing.state(), MessageState.CLASSIFIED,
                "CLASSIFIED",
                result.classification() + " via rule '" + result.matchedRule() + "' → " + result.action());

        metrics.classified(result.classification());
        routingService.route(classified, record.value(), h);

        log.info("Classified {} as {} -> action {} (rule '{}')",
                correlationId, result.classification(), result.action(), result.matchedRule());
    }
}
