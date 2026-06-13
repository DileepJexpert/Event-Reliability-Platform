package com.example.dlqsample;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tiny control surface so you can fire sample failures on demand (in addition to the one-of-each
 * batch published at startup). Everything is POST except the help page at {@code GET /}.
 */
@RestController
public class SampleController {

    private final DlqFailureProducer producer;

    public SampleController(DlqFailureProducer producer) {
        this.producer = producer;
    }

    @GetMapping("/")
    public Map<String, Object> help() {
        return Map.of(
                "dlqTopic", producer.topic(),
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
    public Map<String, Object> sendStorm(@RequestParam(defaultValue = "600") int count) {
        SampleFailure template = SampleFailures.transientTimeout();
        int sent = producer.sendStorm(template, count);
        return Map.of(
                "sent", sent,
                "rootCause", template.exceptionClass(),
                "note", "expect an incident once the count crosses the platform's pattern threshold"
                        + " (default 500 in a 5m window; lower it with RELIABILITY_PATTERN_THRESHOLD)");
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
