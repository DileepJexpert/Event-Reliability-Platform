# CLAUDE.md

Guidance for working in this repository.

## What this is

The **Event Reliability Platform ("Brod")** — a self-hosted system that turns Kafka dead-letter queues
into automatic triage-and-recovery. Two deployables only: a **Spring Boot backend** (`backend/`) and a
**Flutter console** (`frontend/`). Read [README.md](README.md) and [docs/](docs) first.

## Hard constraints (do not violate)

* **Monolith**: exactly one backend JAR + one Flutter app. No microservices.
* **Plain Apache Kafka only** — no Confluent (no Schema Registry, ksqlDB, Connect, Cloud).
* **No external database** — state lives in compacted Kafka topics + Kafka Streams state stores.
* **No external observability/alerting/UI tooling** — Micrometer/Actuator + Flutter only.

## Build & test (backend)

```bash
cd backend
mvn -q -DskipTests compile     # compile
mvn -q test                    # fast unit tests (Surefire)
mvn -q verify                  # full suite: unit + 9 @EmbeddedKafka integration tests (*IT, Failsafe)
```

Integration tests are named `*IT` and run under Failsafe (`mvn verify`), not Surefire (`mvn test`).
Tests need no external Kafka. Running the service does: `docker compose up -d` (KRaft broker on
`localhost:9092`), then `mvn spring-boot:run`.

## Frontend

Flutter is **not installed in the CI/web environment**, so the console can be edited but not compiled
here. To build it locally: `cd frontend && flutter create . && flutter pub get && flutter run`.

## Conventions

* Java 21, records for immutable domain/DTO types, constructor injection, no Lombok.
* Topic names resolved via `TopicNames` (configurable prefix) — never hard-code topic strings.
* Retry timing is **data-driven** (`x-eligible-at`) via the pause/seek delay loop — never add
  `@Scheduled` for the retry clock (only for idempotent housekeeping).
* Every mutating action writes an audit event attributing the acting user.
* Add an `@EmbeddedKafka` integration test for new end-to-end behaviour (see `backend/src/test`).
