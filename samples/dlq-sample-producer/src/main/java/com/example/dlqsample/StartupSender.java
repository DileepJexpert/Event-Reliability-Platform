package com.example.dlqsample;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * On startup, publishes one failure of each kind so the platform lights up immediately. Disable with
 * {@code dlq.autosend-on-startup=false} (env {@code DLQ_AUTOSEND=false}) if you'd rather drive it
 * entirely through the REST endpoints.
 */
@Component
@ConditionalOnProperty(name = "dlq.autosend-on-startup", havingValue = "true", matchIfMissing = true)
public class StartupSender implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupSender.class);

    private final DlqFailureProducer producer;

    public StartupSender(DlqFailureProducer producer) {
        this.producer = producer;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Auto-sending one of each sample failure to '{}' ...", producer.topic());
        try {
            SampleFailures.oneOfEach().forEach(producer::send);
            log.info("Done. Inspect them: http://localhost:8080/api/failures"
                    + "   (POST http://localhost:8081/send/storm to raise an incident)");
        } catch (RuntimeException ex) {
            // Don't crash the app just because the platform isn't up yet — log a hint and stay alive
            // so the user can start the backend and retry via the REST endpoints.
            log.error("Could not publish samples: {}", ex.getMessage());
            log.error("Once Kafka and the platform backend are up, retry with: "
                    + "curl -X POST http://localhost:8081/send/all");
        }
    }
}
