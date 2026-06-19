package com.brod.starter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Onboarding configuration ({@code brod.*}).
 *
 * @param dlqTopic    Brod's inbound DLQ topic that exhausted-retry records are published to.
 * @param retries     number of re-attempts before a record is sent to the DLQ.
 * @param backoffMs   delay between re-attempts, in millis.
 * @param autoDlq     wire the auto-DLQ error handler into the Kafka listener factory (off to opt out).
 * @param idempotency dedup store configuration (see {@link IdempotencyStore}).
 */
@ConfigurationProperties("brod")
public record BrodProperties(
        @DefaultValue("reliability.dlq.inbound") String dlqTopic,
        @DefaultValue("2") int retries,
        @DefaultValue("1000") long backoffMs,
        @DefaultValue("true") boolean autoDlq,
        @DefaultValue Idempotency idempotency) {

    /**
     * @param store     {@code in-memory} (default) or {@code aerospike}.
     * @param aerospike Aerospike connection/store settings (used when {@code store=aerospike}).
     */
    public record Idempotency(
            @DefaultValue("in-memory") String store,
            @DefaultValue Aerospike aerospike) {

        /**
         * @param hosts      comma-separated {@code host:port} list (port defaults to 3000).
         * @param namespace  Aerospike namespace.
         * @param set        set name for idempotency keys.
         * @param ttlSeconds dedup-window TTL in seconds (0 = namespace default, -1 = never expire).
         */
        public record Aerospike(
                @DefaultValue("localhost:3000") String hosts,
                @DefaultValue("brod") String namespace,
                @DefaultValue("idempotency") String set,
                @DefaultValue("604800") int ttlSeconds) {
        }
    }
}
