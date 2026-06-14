package com.example.dlqsample;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Plays a real owning application's consumer for the business topic this sample produces to
 * (POST /produce). It simulates processing: unless the payload explicitly says
 * {@code "simulateFailure": false}, it throws — so Spring Kafka retries it, and once the attempts
 * configured by {@code sim.max-attempts} are exhausted the record is routed to the platform DLQ (see
 * {@link SimConsumerConfig}).
 *
 * <p>Because the failure is driven by the payload, correcting it — here, or via the platform's
 * correct-and-replay (which re-delivers to this same business topic) — lets the message succeed,
 * closing the loop: produce → fail → retry → DLQ → triage → fix → replay → processed OK.
 */
@Component
public class SimulatedConsumer {

    private static final Logger log = LoggerFactory.getLogger(SimulatedConsumer.class);

    private final ObjectMapper mapper;

    public SimulatedConsumer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @KafkaListener(topics = "${sim.business-topic}", id = "sim-business")
    public void onMessage(ConsumerRecord<String, String> record) {
        boolean fail = shouldFail(record.value());
        log.info("RECV business <- topic={} key={} fail={}", record.topic(), record.key(), fail);
        if (fail) {
            throw new SimulatedProcessingException(
                    "simulated downstream failure while processing key=" + record.key());
        }
        log.info("Processed OK: key={} payload={}", record.key(), record.value());
    }

    /** Fail by default; succeed only when the payload explicitly carries {@code "simulateFailure": false}. */
    private boolean shouldFail(String payload) {
        if (payload == null) {
            return true;
        }
        try {
            JsonNode node = mapper.readTree(payload);
            if (node.has("simulateFailure")) {
                return node.get("simulateFailure").asBoolean(true);
            }
        } catch (Exception ignored) {
            // Non-JSON payloads can't be "corrected" — treat them as failing messages.
        }
        return true;
    }
}
