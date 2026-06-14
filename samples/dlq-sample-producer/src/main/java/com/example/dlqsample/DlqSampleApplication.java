package com.example.dlqsample;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Sample "owning application" for the Event Reliability Platform.
 *
 * <p>It does NOT consume anything — it only plays the role of a business service that has given up on
 * a message and republishes it to the platform's DLQ topic ({@code reliability.dlq.inbound})
 * with the failure-header contract. Run it against a local broker + the platform backend and watch
 * the failures get classified, retried, routed or parked.
 */
@SpringBootApplication
public class DlqSampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(DlqSampleApplication.class, args);
    }
}
