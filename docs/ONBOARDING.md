# Onboarding an application

Integrating an owning application with the Event Reliability Platform requires adopting **one thing**:
the failure-header contract. When your consumer cannot process a message, republish it to the
platform's inbound topic with these headers. The platform reasons over the headers; your payload is
treated as **opaque bytes**.

## The failure-header contract (§6.3)

Publish to `reliability.dlq.inbound` (default prefix) with:

| Header | Required | Meaning |
| --- | --- | --- |
| `x-correlation-id` | ✅ | Stable id for this message; the platform's state key. |
| `x-original-topic` | ✅ | Topic the message came from (the re-drive destination). |
| `x-original-partition`, `x-original-offset` | optional | Where it failed (diagnostics). |
| `x-exception-class` | ✅ | Fully-qualified exception class (drives classification). |
| `x-exception-message` | optional | Exception message (secondary classification signal). |
| `x-stacktrace` | optional | Truncated stack trace (diagnostics). |
| `x-attempt-count` | ✅ | Attempts so far. **Echo back the value the platform sent you** on re-drive; start at `1` on the first failure. |
| `x-first-failed-at` | optional | Epoch millis of the first failure. |
| `x-source-app` | optional | Your application/producer id. |
| `x-schema-version`, `x-payload-hash` | optional | Used for schema-drift inference (no Schema Registry). |
| `x-original-key` | optional | Original Kafka key — set this if source-topic ordering matters on re-drive. |

The platform sets `x-eligible-at` (scheduling) and `x-classification` (after classify) itself — you
don't.

## Two integration requirements

### 1. Idempotency (mandatory — §10/§18.3)

When a retry is due, the platform **re-drives the original message back to your source topic** and your
existing handler reprocesses it. A re-driven message **may duplicate one that actually succeeded**
(the platform cannot observe your success). **Your consumer must be idempotent** — processing the same
`x-correlation-id` twice must be safe (e.g. dedupe on a business key, use upserts, or guard with an
idempotency table/cache).

### 2. Let the platform count attempts

Do **not** track your own retry counts or re-drive yourself. The platform is the single brain counting
attempts and deciding tier escalation — this is what prevents infinite retry loops. On failure, simply
republish to the inbound topic with `x-attempt-count` set to whatever value you received (or `1` on the
first failure).

## What the platform does and does not do

* It **does not** run your business logic. It re-drives messages to your topic; your handler runs as
  normal.
* It **does** classify, retry with backoff, detect systemic patterns, route business/poison/unknown
  failures, and keep an immutable audit trail.
* Business failures are routed to `reliability.business.routed` with an `x-business-reason` header for
  your team to handle — retrying them never helps.

## Example: republish on failure (Spring Kafka)

```java
// Inside your consumer's error handler, when processing fails:
ProducerRecord<String, byte[]> failure =
        new ProducerRecord<>("reliability.dlq.inbound", null, correlationId, originalPayloadBytes);
Headers h = failure.headers();
h.add("x-correlation-id",  correlationId.getBytes(UTF_8));
h.add("x-original-topic",  record.topic().getBytes(UTF_8));
h.add("x-original-partition", Integer.toString(record.partition()).getBytes(UTF_8));
h.add("x-original-offset", Long.toString(record.offset()).getBytes(UTF_8));
h.add("x-exception-class", ex.getClass().getName().getBytes(UTF_8));
h.add("x-exception-message", String.valueOf(ex.getMessage()).getBytes(UTF_8));
h.add("x-attempt-count",   Integer.toString(incomingAttemptCountOr1).getBytes(UTF_8));
h.add("x-source-app",      "payment-service".getBytes(UTF_8));
kafkaTemplate.send(failure);
```

On re-drive the platform sends the same payload back to `x-original-topic` with an incremented
`x-attempt-count`; if it fails again, copy that header through when you republish, and the platform
escalates to the next backoff tier automatically.
