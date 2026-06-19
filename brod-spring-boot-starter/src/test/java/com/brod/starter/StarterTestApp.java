package com.brod.starter;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Minimal app for the auto-DLQ test: a listener that always fails, so retries exhaust. */
@SpringBootApplication
public class StarterTestApp {

    @Component
    static class FailingListener {
        @KafkaListener(topics = "app.input", groupId = "starter-it")
        void onMessage(String value) {
            throw new IllegalStateException("boom: " + value);
        }
    }
}
