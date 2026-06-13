package com.example.dlqsample;

import java.util.List;

/**
 * A small catalog of failure templates — one per lane the platform's default classification rules
 * (classification-rules.yml) route to. Sending one of each exercises the whole pipeline.
 */
public final class SampleFailures {

    private SampleFailures() {
    }

    /** TRANSIENT → AUTO_RETRY: a {@code *Timeout*} exception, re-driven through the 5s→1m→5m→30m tiers. */
    public static SampleFailure transientTimeout() {
        return new SampleFailure(
                "transient",
                "payments.transactions",
                "org.example.payments.PaymentGatewayTimeoutException",
                "Timed out after 5000ms calling payment-gateway POST /charge",
                "{\"paymentId\":\"PAY-91823\",\"amount\":4999,\"currency\":\"INR\"}",
                "TRANSIENT -> AUTO_RETRY (re-driven on the retry tiers, then presumed-resolved)");
    }

    /** INFRASTRUCTURE → AUTO_RETRY: a connectivity exception. */
    public static SampleFailure infrastructure() {
        return new SampleFailure(
                "infrastructure",
                "orders.events",
                "java.net.ConnectException",
                "Connection refused: inventory-service/10.0.4.12:8080",
                "{\"orderId\":\"ORD-55012\",\"sku\":\"SKU-7\",\"qty\":2}",
                "INFRASTRUCTURE -> AUTO_RETRY (retried after backoff)");
    }

    /** POISON → QUARANTINE: an undeserializable message — retrying cannot help, so it is parked. */
    public static SampleFailure poison() {
        return new SampleFailure(
                "poison",
                "inventory.updates",
                "com.fasterxml.jackson.databind.exc.MismatchedInputException",
                "Cannot deserialize value of type `int` from String \"NaN\"",
                "{\"sku\":\"SKU-7\",\"qty\":\"NaN\"}",
                "POISON -> QUARANTINE (parked for review; never auto-retried)");
    }

    /** BUSINESS → ROUTE_TO_OWNER: a valid business rejection — retrying will not help. */
    public static SampleFailure business() {
        return new SampleFailure(
                "business",
                "billing.debits",
                "com.example.billing.BusinessException",
                "account frozen: cannot debit account ACC-3391",
                "{\"account\":\"ACC-3391\",\"debit\":12000}",
                "BUSINESS -> ROUTE_TO_OWNER (sent to the business-routed topic)");
    }

    /** UNKNOWN: matches no rule → parked for a human to triage. */
    public static SampleFailure unknown() {
        return new SampleFailure(
                "unknown",
                "sagas.events",
                "java.lang.IllegalStateException",
                "Unexpected saga state PENDING at step COMMIT for saga SAGA-7781",
                "{\"sagaId\":\"SAGA-7781\",\"step\":\"COMMIT\"}",
                "UNKNOWN -> parked for human review (no rule matched)");
    }

    /** One of each — the quickest way to light up every lane. */
    public static List<SampleFailure> oneOfEach() {
        return List.of(transientTimeout(), infrastructure(), poison(), business(), unknown());
    }
}
