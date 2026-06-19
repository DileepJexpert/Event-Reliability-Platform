package com.brod.starter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Onboarding configuration ({@code brod.*}).
 *
 * @param dlqTopic  Brod's inbound DLQ topic that exhausted-retry records are published to.
 * @param retries   number of re-attempts before a record is sent to the DLQ.
 * @param backoffMs delay between re-attempts, in millis.
 * @param autoDlq   wire the auto-DLQ error handler into the Kafka listener factory (turn off to opt out).
 */
@ConfigurationProperties("brod")
public record BrodProperties(
        @DefaultValue("reliability.dlq.inbound") String dlqTopic,
        @DefaultValue("2") int retries,
        @DefaultValue("1000") long backoffMs,
        @DefaultValue("true") boolean autoDlq) {
}
