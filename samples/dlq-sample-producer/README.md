# dlq-sample-producer

A tiny standalone Spring Boot app that plays the role of an **owning application** for the
**Event Reliability Platform**. It does not consume anything — it republishes "failed" events to the
platform's DLQ topic (`reliability.failures.inbound`) with the full failure-header contract, so you
can watch the platform classify, retry, route, quarantine and park them.

> This is a **test helper**, not part of the platform monolith. Run it as a separate process.

---

## What it sends

One template per classification lane (see `SampleFailures.java`). The lane is decided purely by the
`x-exception-class` header, matched against the platform's `classification-rules.yml`:

| Endpoint | Exception class | Platform outcome |
| --- | --- | --- |
| `/send/transient` | `...PaymentGatewayTimeoutException` | **TRANSIENT → AUTO_RETRY** (5s→1m→5m→30m tiers) |
| `/send/infrastructure` | `java.net.ConnectException` | **INFRASTRUCTURE → AUTO_RETRY** |
| `/send/poison` | `...MismatchedInputException` | **POISON → QUARANTINE** (parked) |
| `/send/business` | `...BusinessException` ("account frozen") | **BUSINESS → ROUTE_TO_OWNER** |
| `/send/unknown` | `java.lang.IllegalStateException` | **UNKNOWN → parked** for human review |
| `/send/all` | one of each | all of the above |
| `/send/storm?count=600` | many identical timeouts | trips windowed **pattern detection → raises an incident** |

By default it also auto-sends one of each on startup (`DLQ_AUTOSEND=false` to disable).

---

## Prerequisites

- Java 21 and Maven (you already have these — the platform needs them).
- The platform's **Kafka broker running** and the **backend running** (the backend provisions the
  topics; the broker has auto-create disabled, so the DLQ topic doesn't exist until then).

---

## Run it (end to end)

You need three things up, in this order: **Kafka → platform backend → this sample.**

**1) Start Kafka.** This folder bundles its own copy of the broker compose, so you can start it right
here (it's the same single-node `erp-kafka` broker on `localhost:9092`):

```bash
cd dlq-sample-producer
docker compose up -d
```

**2) Start the platform backend** (from the platform repo — it provisions the `reliability.*` topics
and begins consuming; the broker has auto-create disabled, so this step is what creates the DLQ topic):

```bash
cd backend && mvn spring-boot:run
```

**3) Start this sample** (second terminal, from this folder):

```bash
cd dlq-sample-producer
mvn spring-boot:run
```

> Already running Kafka from the platform repo's own `docker compose up -d`? Then skip step 1 — it's
> the identical broker, and running both would clash on port 9092.

On startup it publishes one of each failure. Send more on demand:

```bash
curl -X POST http://localhost:8081/send/all
curl -X POST http://localhost:8081/send/business
curl -X POST "http://localhost:8081/send/storm?count=600"
```

`GET http://localhost:8081/` prints the endpoint list and the topic it's using.

---

## See the results

The backend runs with auth disabled by default (the `secure` profile turns on OIDC), so you can curl
the API directly:

```bash
# All ingested failures (filterable: ?status=&topic=&classification=)
curl -s http://localhost:8080/api/failures | jq

# One failure with its full audit timeline
curl -s http://localhost:8080/api/failures/<correlationId> | jq

# Incidents raised by pattern detection
curl -s http://localhost:8080/api/incidents | jq

# Platform metrics
curl -s http://localhost:8080/actuator/prometheus | grep reliability
```

Or watch the output topics straight off the broker (no app needed):

```bash
docker exec -it erp-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --from-beginning \
  --topic reliability.parked            # quarantined + unknown land here
# also try: reliability.business.routed , reliability.audit , reliability.incidents
```

**Tip — quick incident demo:** the pattern threshold defaults to 500 in a 5-minute window. To see an
incident without sending hundreds of messages, start the backend with a low threshold and send a small
storm:

```bash
# backend terminal
RELIABILITY_PATTERN_THRESHOLD=20 mvn spring-boot:run
# sample terminal
curl -X POST "http://localhost:8081/send/storm?count=30"
```

---

## Configuration

| Env var | Default | Meaning |
| --- | --- | --- |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Broker to publish to. |
| `DLQ_TOPIC` | `reliability.failures.inbound` | DLQ topic — must match the backend's `reliability.topic-prefix` + `failures.inbound`. |
| `DLQ_SOURCE_APP` | `sample-payments-service` | Value of the `x-source-app` header. |
| `DLQ_AUTOSEND` | `true` | Send one of each on startup. |
| `SERVER_PORT` | `8081` | This app's HTTP port (8080 is the platform). |

---

## The header contract (the only thing a real app must adopt)

When your consumer gives up on a message, republish it to `reliability.failures.inbound` with these
headers (see `FailureHeaders.java` / `DlqFailureProducer.java`):

| Header | Required | Notes |
| --- | --- | --- |
| `x-correlation-id` | yes | stable id; the platform keys all state on it |
| `x-original-topic` | recommended | where it failed (used for replay) |
| `x-original-partition` / `x-original-offset` | optional | original coordinates |
| `x-exception-class` | yes | drives classification |
| `x-exception-message` | yes | drives classification; shown in the console |
| `x-stacktrace` | optional | diagnostics |
| `x-attempt-count` | optional | defaults to 1 |
| `x-first-failed-at` | optional | epoch millis; defaults to ingest time |
| `x-source-app` | recommended | owning app name |
| `x-schema-version` | optional | part of the root-cause signature |

The message **body is opaque** to the platform — send whatever your real payload is.
