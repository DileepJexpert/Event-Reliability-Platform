# Event Reliability Platform — "Brod"

> A self-hosted platform that turns Kafka dead-letter queues from a silent graveyard into an
> **automatic triage-and-recovery system**: recoverable failures fix themselves, systemic failures
> are caught as patterns within minutes, and a human only ever touches the genuinely ambiguous
> few — with a full, immutable audit trail of every decision.

Named after **Max Brod**, who rescued Kafka's manuscripts from being burned.

> 🏃 **Running it locally?** See **[HOW_TO_RUN.md](HOW_TO_RUN.md)** — the full step-by-step runbook
> (Kafka → backend → sample producer → Flutter console), with troubleshooting.

---

## The problem it retires

In a high-throughput event system (payments, account events) a small fraction of messages fail
processing and land in a dead-letter topic nobody watches. This platform fixes the four pains:

1. **Undifferentiated pile** — every failure looks the same in the DLQ even though they need opposite
   treatment. → *Automatic classification into a taxonomy and routing into the right lane.*
2. **Recoverable failures becoming incidents** — a transient blip sits for hours as a stuck
   transaction. → *Tiered automatic retry with backoff until it self-heals or exhausts.*
3. **Systemic breaks discovered a day late** — an upstream schema change silently generates thousands
   of identical failures. → *Near-real-time pattern detection + one-click bulk recovery.*
4. **Manual CLI replay with no record** — no audit trail. → *Immutable audit log of every failure and
   every action, attributing the acting user.*

## Hard constraints (treated as features — §4)

| Constraint | How it's met |
| --- | --- |
| **Monolithic — exactly two deployables** | One Spring Boot backend (`backend/`) + one Flutter console (`frontend/`). |
| **Backend: Java + Spring Boot only** | Everything (ingest, classify, retry, remediate, state, audit, pattern detection, control plane, API, metrics) is one JAR. |
| **Plain Apache Kafka only** | Kafka clients + Streams + AdminClient + consumer pause/seek. **No** Schema Registry, ksqlDB, Connect, or Confluent Cloud. |
| **No external database** | State lives in **compacted Kafka topics** + in-process Kafka Streams state stores (embedded RocksDB). |
| **No external observability / alerting / UI tooling** | Micrometer + Spring Actuator; dashboards are Flutter screens; a tiny in-process notifier. |

The small audit/vendor surface these constraints create is the platform's commercial advantage for
regulated buyers.

---

## Architecture at a glance

```
 owning apps ──(failure-header contract)──▶  reliability.failures.inbound
                                                     │
        ┌────────────────────────────────────────────┼─────────────────────────────────────┐
        │  ONE Spring Boot monolith (N replicas, one consumer group)                          │
        │                                                                                     │
        │  Ingestion → state(RECEIVED)+audit → classify(async) → route by taxonomy:           │
        │     TRANSIENT/INFRA → tiered retry topics ──(pause/seek delay loop)──▶ re-drive to   │
        │                        source; escalate tiers; park on exhaustion                    │
        │     BUSINESS → reliability.business.routed (with reason)                              │
        │     POISON   → reliability.parked (quarantine)                                       │
        │     UNKNOWN  → reliability.parked (human review)                                     │
        │                                                                                     │
        │  Kafka Streams (independent):  inbound ─▶ windowed count by root-cause signature ─▶   │
        │                                 Incident ─▶ views.incidents + incidents feed          │
        │                                                                                     │
        │  Read models (GlobalKTable, full-local, no RPC): state, views.audit, views.incidents │
        │  Control plane: reliability.control.commands ─▶ replay / bulk-replay / quarantine     │
        │  REST + SSE API  ·  Micrometer/Actuator  ·  OIDC resource server (VIEWER/OPERATOR)    │
        └─────────────────────────────────────────────────────────────────────────────────────┘
                                                     │
                                       Flutter console (REST + SSE only)
```

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the module-by-module design, the lifecycle
state machine, the Streams topology and the GlobalKTable rationale.

## Repository layout

```
backend/    Spring Boot 3 / Java 21 monolith (the platform). Compiles & tested here.
frontend/   Flutter operations console (authored; needs a Flutter SDK to build — see its README).
docs/        Architecture and onboarding guides.
docker-compose.yml   Local single-node Apache Kafka (KRaft, auto-create disabled).
```

---

## Quick start

### 1. Start Kafka

```bash
docker compose up -d        # single-node Apache Kafka (KRaft) on localhost:9092
```

### 2. Run the backend

```bash
cd backend
mvn spring-boot:run         # provisions all topics via AdminClient, then starts consuming
```

Health & metrics: <http://localhost:8080/actuator/health> ·
<http://localhost:8080/actuator/prometheus>

### 3. Produce a test failure

Republish any message to `reliability.failures.inbound` with the failure headers (§6.3). For example
with the console producer (headers support varies by tool) or from a small app — see
[`docs/ONBOARDING.md`](docs/ONBOARDING.md) for the exact header contract and a producer snippet.

### 4. Run the console

```bash
cd frontend
flutter create .            # generate platform scaffolding around lib/ + pubspec
flutter pub get
flutter run -d chrome --dart-define=API_BASE_URL=http://localhost:8080 --dart-define=AUTH_MODE=dev
```

---

## API surface (§15)

| Method & path | Purpose | Role (secure profile) |
| --- | --- | --- |
| `GET /api/failures?status=&topic=&classification=&page=&size=` | List / filter failures | VIEWER+ |
| `GET /api/failures/{correlationId}` | Detail incl. full audit timeline | VIEWER+ |
| `GET /api/incidents` · `GET /api/incidents/{id}` | Active systemic incidents | VIEWER+ |
| `POST /api/failures/{correlationId}/replay` | Single replay (audited) | OPERATOR |
| `POST /api/failures/{correlationId}/quarantine` | Manual quarantine (audited) | OPERATOR |
| `POST /api/incidents/{id}/bulk-replay` | One-click cohort recovery (audited) | OPERATOR |
| `GET /api/stream` | SSE live feed (`failure` / `incident` events) | VIEWER+ |
| `GET /actuator/health` · `/actuator/prometheus` · `/actuator/metrics` | Self-contained observability | — |

Mutating endpoints publish a command to `reliability.control.commands` (carrying the acting user) and
return **202 Accepted**; exactly one instance executes it.

## Security profiles (§17)

* **default / local** — permissive, so the console and tests run without an IdP.
* **`secure`** (`--spring.profiles.active=secure`) — OIDC resource server validating bank-SSO JWTs;
  `VIEWER` may read, `OPERATOR` may perform mutations. Set `OIDC_ISSUER_URI`. Every mutating action is
  authorized **and** audited with the acting user.

## Testing

```bash
cd backend && mvn verify     # full suite; `mvn test` runs only the fast unit tests
```

16 tests run against an **in-process Kafka broker** (`@EmbeddedKafka`, no external Kafka needed): 7
fast unit tests (Surefire) for the classifier and SSE hub, plus 9 integration tests (`*IT`, Failsafe)
covering the whole platform end to end — ingestion → classification → routing into all four lanes, the
tiered-retry pause/seek delay loop → re-drive → presumed-resolved, windowed pattern detection raising
an incident, the control plane over real HTTP (single + bulk replay with audit), and the observability
surface (Actuator health + Prometheus meters).

## Configuration highlights (`reliability.*`, see `backend/src/main/resources/application.yml`)

| Key | Default | Meaning |
| --- | --- | --- |
| `reliability.topic-prefix` | `reliability.` | Prefix for every platform topic. |
| `reliability.retry.tiers` | `5s, 1m, 5m, 30m` | Backoff tiers (one topic each). |
| `reliability.retry.max-pause` | `30s` | Max single pause in the delay loop (re-polls to stay alive). |
| `reliability.pattern.window` / `.threshold` | `5m` / `500` | Incident detection window & threshold. |
| `reliability.notifier.*` | disabled / `log` | Incident notifier (`log` or `webhook`). |
| `reliability.housekeeping.*` | – | Sweep interval, resolve-grace, terminal retention (TTL). |

Classification rules are externalised in
[`backend/src/main/resources/classification-rules.yml`](backend/src/main/resources/classification-rules.yml)
— edit them without redeploying. Classification is **fully deterministic** (no AI): every decision
traces to a named rule in the audit log.

## Correctness guarantees called out by the spec (§18)

1. **`poll()` stays alive while partitions are paused** — the retry delay loop uses
   `Acknowledgment.nack(Duration)` (pause + re-seek while polling), never sleeps the consumer thread,
   never breaches `max.poll.interval`.
2. **No `@Scheduled` for retry timing** — timing is data-driven from `x-eligible-at` and decided by
   the partition-owning consumer, so N instances don't race. `@Scheduled` is used only for idempotent
   housekeeping.
3. **Onboarded consumers must be idempotent** — the re-drive model may duplicate a message that
   actually succeeded (documented in `docs/ONBOARDING.md`).
4. **Idempotent producer** is enabled for republish + state writes; dedupe by correlation id.
5. **Topics provisioned explicitly via AdminClient** at startup (auto-create assumed off).
6. **Horizontal scale = N instances, one group**; console reads use **GlobalKTable** full-local views.
7. **Classification rules externalised / config-driven.**
8. **No Confluent components, no external DB, no external observability/alerting/UI toolkit.**

## Status

All nine build phases (§20) are implemented in the backend and verified by the test suite; the
Flutter console covers all six screens. See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for design
detail and known trade-offs.
