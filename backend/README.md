# Event Reliability Service (`event-reliability-service`)

The single backend monolith for the Event Reliability Platform — Java 21 / Spring Boot 3 / Maven.
See the [root README](../README.md) for the product overview and [docs/](../docs) for the design.

## Build & test

```bash
mvn clean test        # fast unit tests (Surefire)
mvn clean verify      # full suite: 7 unit + 9 @EmbeddedKafka integration tests (*IT, Failsafe)
mvn clean package     # builds target/event-reliability-service.jar
```

Integration tests are named `*IT` and run under the Failsafe plugin (`mvn verify`). No external Kafka
is needed for the tests. To run the service you do need a broker (`docker compose up -d` from the repo
root starts one on `localhost:9092`).

## Run

```bash
# Local / dev (permissive security, no IdP required):
mvn spring-boot:run

# Or the built jar, against a specific broker:
java -jar target/event-reliability-service.jar \
  --spring.kafka.bootstrap-servers=broker:9092

# Secure (OIDC resource server + VIEWER/OPERATOR roles):
java -jar target/event-reliability-service.jar \
  --spring.profiles.active=secure \
  --OIDC_ISSUER_URI=https://sso.bank.internal/realms/payments
```

On startup the AdminClient provisioner creates every platform topic with the correct cleanup policy
(compacted `state`/`views.*`, append-only long-retention `audit`, work-queue topics, one topic per
retry tier).

## Key configuration

All under `reliability.*` in `src/main/resources/application.yml` (overridable via env vars):
topic prefix & partitions, retry tiers & max-pause, pattern window/threshold, notifier, housekeeping,
replay destination. Classification rules live in `src/main/resources/classification-rules.yml` and can
be edited without recompiling.

Common env vars: `KAFKA_BOOTSTRAP_SERVERS`, `RELIABILITY_GROUP_ID`,
`RELIABILITY_PATTERN_THRESHOLD`, `RELIABILITY_NOTIFIER_ENABLED`, `SERVER_PORT`.

## Package map

`config` · `domain` · `ingestion` · `classification` · `routing` · `retry` · `streams` · `state` ·
`audit` · `control` · `observability` · `housekeeping` · `api` · `security` · `common`. See
[docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md) for what each does.

## Observability

`/actuator/health`, `/actuator/metrics`, `/actuator/prometheus`. Custom meters: `erp.failures.*`,
`erp.retry.*`, `erp.replays`, `erp.incidents.active`. A live SSE feed is at `GET /api/stream`.
