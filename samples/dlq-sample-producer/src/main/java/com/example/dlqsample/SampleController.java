package com.example.dlqsample;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tiny control surface so you can fire sample failures on demand (in addition to the one-of-each
 * batch published at startup). Everything is POST except the help page at {@code GET /}.
 */
@RestController
public class SampleController {

    private final DlqFailureProducer producer;
    private final KafkaTemplate<String, String> kafka;
    private final String businessTopic;

    public SampleController(DlqFailureProducer producer, KafkaTemplate<String, String> kafka,
                           @Value("${sim.business-topic}") String businessTopic) {
        this.producer = producer;
        this.kafka = kafka;
        this.businessTopic = businessTopic;
    }

    @GetMapping("/")
    public Map<String, Object> help() {
        return Map.of(
                "dlqTopic", producer.topic(),
                "realisticFlow", "POST /produce  (body = your JSON payload) -> produced to '" + businessTopic
                        + "'; the sample consumer retries, then routes failures to the DLQ",
                "send", List.of(
                        "POST /send/transient",
                        "POST /send/infrastructure",
                        "POST /send/poison",
                        "POST /send/business",
                        "POST /send/unknown",
                        "POST /send/all                  (one of each)",
                        "POST /send/storm?count=600      (shared root cause -> raises an incident)"),
                "observePlatform", List.of(
                        "GET http://localhost:8080/api/failures",
                        "GET http://localhost:8080/api/incidents",
                        "GET http://localhost:8080/actuator/prometheus"));
    }

    @PostMapping("/send/transient")
    public Map<String, String> sendTransient() {
        return one(SampleFailures.transientTimeout());
    }

    @PostMapping("/send/infrastructure")
    public Map<String, String> sendInfrastructure() {
        return one(SampleFailures.infrastructure());
    }

    @PostMapping("/send/poison")
    public Map<String, String> sendPoison() {
        return one(SampleFailures.poison());
    }

    @PostMapping("/send/business")
    public Map<String, String> sendBusiness() {
        return one(SampleFailures.business());
    }

    @PostMapping("/send/unknown")
    public Map<String, String> sendUnknown() {
        return one(SampleFailures.unknown());
    }

    @PostMapping("/send/all")
    public List<Map<String, String>> sendAll() {
        return SampleFailures.oneOfEach().stream().map(this::one).toList();
    }

    @PostMapping("/send/storm")
    public Map<String, Object> sendStorm(@RequestParam(name = "count", defaultValue = "600") int count) {
        SampleFailure template = SampleFailures.transientTimeout();
        int sent = producer.sendStorm(template, count);
        return Map.of(
                "sent", sent,
                "rootCause", template.exceptionClass(),
                "note", "expect an incident once the count crosses the platform's pattern threshold"
                        + " (default 500 in a 5m window; lower it with RELIABILITY_PATTERN_THRESHOLD)");
    }

    /**
     * Realistic flow: produce a business event, let this app's own consumer try (and, by default,
     * fail) to process it, retry, then route it to the platform DLQ. POST your JSON payload as the body
     * (optionally {@code ?topic=}). Add {@code "simulateFailure": false} to the payload to make it
     * process successfully instead.
     */
    @PostMapping("/produce")
    public Map<String, Object> produce(
            @RequestBody(required = false) String payload,
            @RequestParam(name = "topic", required = false) String topic,
            @RequestHeader(name = "correlationId", required = false) String correlationId) {
        String target = (topic != null && !topic.isBlank()) ? topic : businessTopic;
        String body = (payload != null && !payload.isBlank()) ? payload : "{\"demo\":true}";
        // Stamp the caller's correlation id (or a generated one) as the record key AND `correlationId`
        // header on the business message, so it flows through the consumer to the DLQ and the platform
        // keys all state on it — find this exact id on the dashboard.
        String id = (correlationId != null && !correlationId.isBlank())
                ? correlationId : UUID.randomUUID().toString();
        ProducerRecord<String, String> record = new ProducerRecord<>(target, null, id, body);
        record.headers().add("correlationId", id.getBytes(StandardCharsets.UTF_8));
        kafka.send(record);
        return Map.of(
                "producedTo", target,
                "correlationId", id,
                "flow", "produce -> sample consumer retries -> routes to DLQ (" + producer.topic()
                        + ") -> platform ingests",
                "watch", "http://localhost:8080/api/failures/" + id,
                "tip", "pass a 'correlationId' request header to track your own id; add"
                        + " \"simulateFailure\": false to the payload (or correct it on replay) to succeed");
    }

    private Map<String, String> one(SampleFailure f) {
        String correlationId = producer.send(f);
        return Map.of(
                "label", f.label(),
                "correlationId", correlationId,
                "exceptionClass", f.exceptionClass(),
                "expected", f.expectedOutcome());
    }
}
