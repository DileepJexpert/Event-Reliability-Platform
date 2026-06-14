# Event Reliability Platform ("Brod") — Product & Demo Guide

> One-line pitch: **Brod turns your Kafka dead-letter queues into automatic triage-and-recovery** —
> failures are classified, auto-retried when safe, grouped into incidents when systemic, and replayed
> under bank-grade controls — with a live console and a full audit trail. No database, no external
> tooling: just one Spring Boot service + one Flutter console on plain Apache Kafka.

This single document explains **what the product is for, what you achieve, what every event state
means, how the end-to-end flow works, and a step-by-step demo script.**

---

## 1. The problem it solves

When a microservice can't process a Kafka message, the message usually lands in a **dead-letter queue
(DLQ)** and then… nothing. Someone has to notice, dig through the DLQ, figure out *why* it failed,
decide whether to retry or fix it, and manually replay it — often hours or days later, with no audit
trail and no idea it's affecting hundreds of other messages too.

**Brod automates that whole loop.** Onboarded apps just republish their failures to one DLQ topic with
a small set of headers; Brod does the rest.

---

## 2. What you achieve (the value)

| Outcome | How Brod delivers it |
|---|---|
| **Lower MTTR** | Transient/infra failures auto-retry on tiers (5s→1m→5m→30m) and recover with no human action. |
| **No manual DLQ digging** | A live console lists every failure with its source topic, error, payload and full history. |
| **Catch systemic breaks fast** | Pattern detection raises an **incident** when many failures share one root cause (e.g. an upstream schema change) — minutes, not a day later. |
| **Safe, governed recovery** | **Maker-checker (4-eyes)** replay: one person requests, a *different* person approves; payloads can be corrected before replay. |
| **Full accountability** | Every action is written to an immutable **audit log** attributing the acting user. |
| **Service-owner self-service** | Each team filters the console by **their** source topic / app to see only their failures. |
| **Cheap to run** | A monolith on plain Apache Kafka — **no external database, no Schema Registry, no APM/Grafana**. State lives in compacted Kafka topics. |

---

## 3. Key concepts (glossary)

- **DLQ (dead-letter queue)** — the single entry-point topic `reliability.dlq.inbound`. Every onboarded
  app republishes its failed messages here. *Brod consumes only this topic, not your business topics.*
- **Source / original topic** (`x-original-topic`) — where the message was being consumed when it
  failed (e.g. `payments.transactions`). Shown on the dashboard; replays go back here.
- **Correlation id** (`correlationId`) — a stable id the platform keys *all* state on. Pass it on your
  HTTP call and it flows everywhere, so you can track one event end-to-end.
- **Root-cause signature** — a fingerprint derived from a failure's headers (exception + topic + schema
  version). Identical signatures are what pattern detection counts.
- **Classification** — the taxonomy Brod assigns (see §5) that decides the lane.
- **Maker / Checker** — the two roles in 4-eyes replay: **maker** (Operator) requests, **checker**
  (Approver) approves.

---

## 4. End-to-end flow

```
  YOUR microservice                         BROD platform (one Spring Boot service)
  ─────────────────                         ───────────────────────────────────────────────────────────
  consumes  payments.transactions
        │  (processing fails, app retries, gives up)
        ▼
  republishes the message to ───────────►  reliability.dlq.inbound   ← the single DLQ entry point
   with headers (correlationId,                     │
   x-original-topic, x-exception-*)                 ▼
                                            1) INGESTION        → state: RECEIVED   (+ audit, payload captured)
                                                     │
                                                     ▼
                                            2) CLASSIFICATION   → state: CLASSIFIED (taxonomy + action + reason)
                                                     │
                                                     ▼
                                            3) ROUTING into a lane by classification:
                                               ├─ TRANSIENT / INFRASTRUCTURE → Retry tiers 5s→1m→5m→30m
                                               │       └─ recovers → RESOLVED   |  tiers exhausted → EXHAUSTED_PARKED
                                               ├─ BUSINESS  → business.routed   → ROUTED_BUSINESS
                                               ├─ POISON    → parked            → QUARANTINED_POISON
                                               └─ UNKNOWN   → parked            → PARKED_UNKNOWN

  (in parallel) PATTERN DETECTION reads the same DLQ stream → raises an INCIDENT when one
                root-cause signature crosses the threshold within a time window.

  OPERATOR RECOVERY (4-eyes): pick a failure → maker requests replay (optionally correct payload /
                redirect topic) → a different checker approves → REPLAYED back to the original topic,
                where your consumer reprocesses it.
```

Retry timing is **data-driven** (each message carries `x-eligible-at`); Brod is the single brain
counting attempts via `x-attempt-count`, so it never double-counts or loses track across restarts.

---

## 5. Failure classification (taxonomy)

Brod classifies each failure from its `x-exception-class` / `x-exception-message` against editable
rules (`classification-rules.yml`). The class decides the lane:

| Classification | Meaning | Retryable? | Recommended action | Ends up |
|---|---|---|---|---|
| **TRANSIENT** | Downstream blip / timeout; likely succeeds on retry | ✅ | `AUTO_RETRY` | retry tiers → `RESOLVED` |
| **INFRASTRUCTURE** | Connectivity / broker / resource issue | ✅ | `AUTO_RETRY` | retry tiers → `RESOLVED` |
| **BUSINESS** | Valid business rejection (account frozen, limit exceeded) — retrying never helps | ❌ | `ROUTE_TO_OWNER` | `ROUTED_BUSINESS` |
| **POISON** | Genuinely malformed; crashes the consumer every time | ❌ | `QUARANTINE` | `QUARANTINED_POISON` |
| **UNKNOWN** | No rule matched; treat conservatively | ❌ | `PARK_FOR_REVIEW` | `PARKED_UNKNOWN` |

---

## 6. Event lifecycle states (what each state means)

```
RECEIVED → CLASSIFIED → (RETRY_SCHEDULED → RETRYING)* → <terminal>
```

### Processing states
| State | Meaning |
|---|---|
| `RECEIVED` | Ingested from the DLQ; recorded with headers + payload. |
| `CLASSIFIED` | Taxonomy, recommended action and reason decided. |
| `RETRY_SCHEDULED` | Queued on a retry tier; waiting for its `x-eligible-at` time. |
| `RETRYING` | Re-driven back to the source topic for the owning consumer to reprocess. |

### Terminal states
| State | Meaning | Lives on topic |
|---|---|---|
| `RESOLVED` | A retry succeeded — the message did not come back as a new failure (presumed resolved after the resolve-grace ~2m). | `state` |
| `EXHAUSTED_PARKED` | All retry tiers used up; parked for a human. | `reliability.parked` |
| `ROUTED_BUSINESS` | Business failure handed to its owner with a reason; no retry. | `reliability.business.routed` |
| `QUARANTINED_POISON` | Malformed message quarantined immediately; never retried. | `reliability.parked` |
| `PARKED_UNKNOWN` | Unclassified; parked for human review (no blind retry). | `reliability.parked` |

### Control-plane states (maker-checker recovery)
| State | Meaning |
|---|---|
| `REPLAY_REQUESTED` | A maker raised a replay request (awaiting a checker). |
| `REPLAY_APPROVED` | A checker approved it (about to dispatch). |
| `BULK_REPLAY_REQUESTED` / `BULK_REPLAY_APPROVED` | Same, for replaying a whole incident cohort at once. |
| `REPLAYED` | The (corrected) message was dispatched back to its source topic. |

> The three **parked** states (`EXHAUSTED_PARKED`, `PARKED_UNKNOWN`, `QUARANTINED_POISON`) are the
> "needs a human" bucket and physically live on `reliability.parked`.

---

## 7. Retry tiers & auto-recovery

- Default tiers: **5s → 1m → 5m → 30m** (configurable). Each tier is its own topic to avoid
  head-of-line blocking.
- A retry that fails again escalates to the next tier; once all tiers are used, the message becomes
  `EXHAUSTED_PARKED`.
- Brod can't see success directly (onboarded apps only adopt the failure contract), so if a re-driven
  message doesn't come back within the **resolve-grace (~2m)**, it's presumed `RESOLVED`.

## 8. Pattern detection & incidents

- Brod continuously counts identical **root-cause signatures** in tumbling windows
  (default **5-minute window, threshold 500**).
- When a signature crosses the threshold, it raises an **Incident** (root cause, count, source topic,
  window) — so a systemic break (e.g. a bad upstream deploy) is caught in minutes.
- Because every underlying message is held by correlation id, the whole cohort can be **bulk-replayed**
  in one click once the upstream is fixed.

## 9. Governed recovery — maker-checker (4-eyes)

Replays are bank-grade by default:
1. A **maker** (Operator) selects a failure and **requests a replay** — optionally **correcting the
   payload** and/or redirecting to an allow-listed topic.
2. A **different checker** (Approver) reviews and **approves** (or rejects / returns for rework).
3. Only then is the (corrected) message **replayed** to its original topic. Both steps are audited.

> A maker cannot approve their own request (distinct-checker rule). Turn approval off for a
> non-regulated/dev deployment with `RELIABILITY_APPROVAL_REQUIRED=false`.

---

## 10. The console (what to show)

Login (dev/demo mode) offers two fixed identities; a top-bar switch hops between them instantly:
- **Alice · Maker** (Operator) — raises replay / quarantine requests.
- **Bob · Checker** (Approver) — approves / rejects requests.

| Screen | What it shows |
|---|---|
| **Dashboard** | KPI cards (total / auto-recovering / needs-attention / active incidents), breakdowns by classification + top source topics + top source apps, and a recent-failures table. Live via SSE. |
| **Failures** | Searchable table — a service owner filters by **source topic** and/or **source app** (+ status/classification) or jumps to a **correlation id**. Click a row for full detail + audit timeline + replay. |
| **Incidents** | Systemic patterns; bulk-replay a cohort. |
| **Approvals** | The maker-checker queue: pending requests, original-vs-corrected payload, approve / reject / return / resubmit, and per-request history. |
| **Audit** | The immutable trail of every transition and action, by user. |

---

## 11. Demo script (≈10 minutes)

**Prep — clean slate (so the dashboard starts empty):**
```bash
docker compose down -v && docker compose up -d           # broker
cd backend && mvn spring-boot:run                          # backend (provisions topics)
cd samples/dlq-sample-producer && DLQ_AUTOSEND=false mvn spring-boot:run   # sample app
# console:
cd frontend && flutter run -d chrome --dart-define=API_BASE_URL=http://localhost:8080 --dart-define=AUTH_MODE=dev
```

**1. Show the realistic flow (produce → consume → retry → DLQ).** From Postman:
```
POST http://localhost:8081/produce
Header:  correlationId: DEMO-001
Body (raw JSON): { "paymentId": "PAY-1", "amount": 4999, "currency": "INR" }
```
The sample produces to `payments.transactions`; its own consumer fails 3× then routes to the DLQ.
Watch the sample log: `RECV business … fail=true (x3) → routing to DLQ`.

**2. See it on the dashboard.** Open the console → **Failures** (or `GET /api/failures/DEMO-001`).
Show: **source topic** `payments.transactions`, the exception, attempts, payload, and the **audit
timeline** (RECEIVED → CLASSIFIED → PARKED_UNKNOWN).

**3. Show service-owner search.** On **Failures**, filter **Source topic = payments.transactions** (or
**Source app = sample-payments-service**) → only that team's failures.

**4. Correct-and-replay (4-eyes).**
   - Top bar → **Alice · Maker** → open `DEMO-001` → **Request replay** → edit the payload to add
     `"simulateFailure": false` → submit (now PENDING).
   - Top bar → **Bob · Checker** → **Approvals** → **Approve**.
   - The message is replayed to `payments.transactions`; the sample consumer logs **Processed OK**.
   - The audit timeline now shows *requested by alice → approved by bob → REPLAYED*.

**5. Show the taxonomy lanes** (optional) — fire one of each and show where they land:
```bash
curl -X POST http://localhost:8081/send/all      # transient / infra / poison / business / unknown
```

**6. Trigger an incident** (optional) — a storm of identical failures crosses the pattern threshold:
```bash
# start backend with a low threshold for the demo: RELIABILITY_PATTERN_THRESHOLD=20
curl -X POST "http://localhost:8081/send/storm?count=30"
```
Show the **Incidents** screen, then **bulk-replay** the cohort.

---

## 12. Architecture at a glance

- **Two deployables only:** a Spring Boot **backend** + a Flutter **console**.
- **Plain Apache Kafka only** (KRaft) — no Confluent, no Schema Registry, no Connect.
- **No external database** — all state lives in **compacted Kafka topics** + Kafka Streams state
  stores; the console's read models are GlobalKTables loaded on startup.
- **Self-contained observability** — Micrometer + Actuator (`/actuator/prometheus`), no external APM.

### Topics
| Topic | Purpose |
|---|---|
| `reliability.dlq.inbound` | **The DLQ** — single entry point for all failures. |
| `reliability.internal.classify` | Async classification hand-off (keeps ingestion fast). |
| `reliability.retry.{5s,1m,5m,30m}` | One topic per retry tier. |
| `reliability.parked` | Quarantined + exhausted + unknown (the "needs a human" bucket). |
| `reliability.business.routed` | Business failures handed to owners. |
| `reliability.state` | Compacted system-of-record per correlation id. |
| `reliability.audit` | Append-only immutable audit log. |
| `reliability.incidents` / `reliability.views.*` | Incident feed + materialized read views. |
| `reliability.control.{commands,requests}` | Maker-checker control plane. |

### The header contract (all an onboarded app must adopt)
| Header | Required | Notes |
|---|---|---|
| `correlationId` | yes | Stable id; platform keys all state on it. (`x-correlation-id` also accepted.) |
| `x-original-topic` | recommended | Source topic — used for replay + the dashboard. |
| `x-exception-class` | yes | Drives classification. |
| `x-exception-message` | yes | Drives classification; shown in the console. |
| `x-attempt-count` | optional | Defaults to 1; Brod increments it across retries. |
| `x-source-app` | recommended | Owning app name (service-owner search). |
| `x-schema-version` | optional | Part of the root-cause signature. |

> The message **body is opaque** to Brod — send whatever your real payload is.

---

## 13. One-liner per audience

- **To an engineer:** "Apps dump failures on one DLQ with a few headers; Brod classifies, auto-retries
  the transient ones, parks the rest, detects systemic incidents, and replays under 4-eyes — all on
  plain Kafka, no DB."
- **To a manager:** "It turns silent DLQ pile-ups into automatic recovery and one-click, audited,
  approved replays — cutting incident time from hours to minutes and giving each team self-service
  visibility into their own failures."
