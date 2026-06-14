# How to run the Event Reliability Platform locally

This is the **living runbook** — the one place that tells you how to bring the whole thing up on your
machine. Keep it updated as the setup changes.

The system has four moving parts when testing locally:

| # | Part | Where | Port |
| --- | --- | --- | --- |
| 1 | **Kafka broker** | `docker-compose.yml` (repo root) | 9092 |
| 2 | **Backend** (the platform) | `backend/` | 8080 |
| 3 | **Sample producer** (feeds test failures) | `samples/dlq-sample-producer/` | 8081 |
| 4 | **Frontend console** (Flutter) | `frontend/` | (dev server) |

Start them **in that order** — each depends on the previous one.

---

## 0. Prerequisites (one-time)

- **Java 21** and **Maven** — `java -version` should show 21
- **Docker** (Docker Desktop on Windows/Mac) — for Kafka
- **Flutter SDK** (only for the frontend) — `flutter --version` (Dart ≥ 3.4), then `flutter doctor`

---

## 1. Start Kafka

From the **repo root** (same level as `backend/` and `frontend/`):

```bash
docker compose up -d
docker compose ps          # erp-kafka should be "running"
```

This is a single-node Apache Kafka (KRaft mode, container `erp-kafka`) on `localhost:9092`.
**Auto-create is disabled on purpose** — the backend creates its own topics (next step).

Stop later with `docker compose down` (add `-v` to wipe data for a clean slate).

---

## 2. Start the backend

```bash
cd backend
mvn spring-boot:run
```

Or from your IDE: run `com.eventreliability.EventReliabilityApplication` as a Java application.
Active profile `local` is fine (it just means "not `secure`" → auth disabled); leaving the profile
blank does the same thing. **Do not** use the `secure` profile locally (that turns on OIDC).

**What happens on startup:** connects to the broker → `TopicProvisioner` creates the `reliability.*`
topics → Kafka Streams topologies + the `@KafkaListener` consumers start → HTTP API + Actuator come up
on `:8080`.

**Verify it's up:**

```bash
curl http://localhost:8080/actuator/health          # {"status":"UP"}
docker exec -it erp-kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --list | grep reliability
curl http://localhost:8080/api/failures             # [] until you send failures
```

---

## 3. Send test failures (sample producer)

The sample app plays an "owning application" that republishes failures to the DLQ topic
(`reliability.failures.inbound`) with the correct headers.

```bash
cd samples/dlq-sample-producer
mvn spring-boot:run
```

It auto-sends one failure of each kind on startup. Send more on demand (note: **PowerShell** users,
keep each command on one line — see Troubleshooting):

```bash
curl -X POST http://localhost:8081/send/all
curl -X POST http://localhost:8081/send/business
curl -X POST "http://localhost:8081/send/storm?count=600"     # raises an incident
```

`GET http://localhost:8081/` lists every endpoint. (Full details in
`samples/dlq-sample-producer/README.md`.)

---

## 4. Start the frontend console (Flutter)

```bash
cd frontend
flutter create .            # one-time: generates web/ android/ ios/ around lib/ + pubspec (won't touch your code)
flutter pub get
```

Then run it. **Put the whole command on one line** (or use PowerShell backticks — see Troubleshooting):

**Web (Chrome):**
```bash
flutter run -d chrome --dart-define=API_BASE_URL=http://localhost:8080 --dart-define=AUTH_MODE=dev
```

**Windows desktop** (alternative — no browser/CORS, needs Visual Studio "Desktop development with C++"):
```bash
flutter run -d windows --dart-define=API_BASE_URL=http://localhost:8080 --dart-define=AUTH_MODE=dev
```

`AUTH_MODE=dev` matches the backend's default/local (no-auth) profile, so no login/token is needed.
The backend now sends **dev CORS headers** under the non-`secure` profile, so the Chrome path works.

---

## Observing results

The backend's default profile has **auth disabled**, so you can curl the API directly:

```bash
curl -s http://localhost:8080/api/failures | jq                 # all failures (?status=&topic=&classification=)
curl -s http://localhost:8080/api/failures/<correlationId> | jq # one failure + full audit timeline
curl -s http://localhost:8080/api/incidents | jq               # incidents from pattern detection
curl -s http://localhost:8080/actuator/prometheus | grep reliability
```

Or read the output topics straight off the broker:

```bash
docker exec -it erp-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --from-beginning --topic reliability.parked
# also: reliability.business.routed , reliability.audit , reliability.incidents
```

**Quick incident demo:** the pattern threshold defaults to 500 / 5-min window. To see an incident
fast, start the backend with a low threshold and send a small storm:

```bash
# backend terminal
RELIABILITY_PATTERN_THRESHOLD=20 mvn spring-boot:run
# sample terminal
curl -X POST "http://localhost:8081/send/storm?count=30"
```

---

## What you should see (per failure type)

Each sample lands in a different lane. After sending, check `GET /api/failures` (open it in a browser,
or curl it) and the matching output topic:

| You send | classification | ends in state | lands on topic | how it gets there |
| --- | --- | --- | --- | --- |
| `/send/transient` | TRANSIENT | `RETRY_SCHEDULED` → `RESOLVED` | `retry.5s` → … | auto-retried; presumed resolved after the resolve grace (~2 min) since nothing re-fails it |
| `/send/infrastructure` | INFRASTRUCTURE | `RETRY_SCHEDULED` → `RESOLVED` | `retry.5s` → … | same retry path |
| `/send/poison` | POISON | `QUARANTINED_POISON` | `parked` | quarantined immediately, never retried |
| `/send/business` | BUSINESS | `ROUTED_BUSINESS` | `business.routed` | handed to the owner; retry wouldn't help |
| `/send/unknown` | UNKNOWN | `PARKED_UNKNOWN` | `parked` | parked for a human (no rule matched) |
| `/send/storm?count=600` | TRANSIENT (×N) | — | `incidents` | identical root cause trips pattern detection → an incident |

Operator actions (each also appended to the audit timeline):

| Action | Result |
| --- | --- |
| `POST /api/failures/{id}/replay` | state → `REPLAY_REQUESTED` → `REPLAYED`; message re-sent to its original topic |
| `POST /api/failures/{id}/quarantine` | manually parked; audit entry attributes you |
| `POST /api/incidents/{id}/bulk-replay` | every example in the incident re-driven in one click |

Every transition is recorded in the immutable audit log — see it at `GET /api/failures/{correlationId}`.

---

## Logs — following the flow (for debugging)

The backend logs the whole message journey at INFO with grep-friendly tags:

| Tag | Meaning |
| --- | --- |
| `RECV <- topic=… key=…` | a listener consumed a record (ingestion, classification, retry, control, incidents) |
| `SEND -> topic=… key=…` | the platform published a record (every publish funnels through one place) |
| `AUDIT … : FROM -> TO [ACTION] by … : …` | a lifecycle transition — the clearest single trace |
| `Classified …` / `Scheduled retry … on tier …` / `Re-drove … -> topic …` / `Parked …` / `Routed business …` | key decisions |

So one failure reads top-to-bottom as: `RECV inbound → SEND state/audit → SEND classify → RECV classify → Classified… → SEND retry.5s → … → RECV retry (eligible) → Re-drove -> payments.transactions`.

Turn the volume down when you don't need it:
- `logging.level.com.eventreliability.common.KafkaPublisher=WARN` — hide the per-publish `SEND` firehose.
- `logging.level.com.eventreliability=WARN` — quiet the platform entirely.

---

## Maker-checker replay (4-eyes)

Replays are bank-grade by default: a **maker** raises a request, a **different checker** approves it,
and only then does it execute — both steps audited. You can also correct the payload and redirect to
an allow-listed topic. Locally there's no IdP, so pass an `X-Actor` header to act as different people:

```powershell
# 1) Maker (alice) requests a replay (optionally correcting payload / redirecting topic)
$req = Invoke-RestMethod -Method Post "http://localhost:8080/api/failures/<id>/replay" `
         -Headers @{ "X-Actor" = "alice" } -ContentType "application/json" `
         -Body (@{ reason = "upstream fixed" } | ConvertTo-Json)
$req.requestId

# 2) See the pending queue, then a DIFFERENT checker (bob) approves → it executes
Invoke-RestMethod "http://localhost:8080/api/approvals"
Invoke-RestMethod -Method Post "http://localhost:8080/api/approvals/$($req.requestId)/approve" `
  -Headers @{ "X-Actor" = "bob" } -ContentType "application/json" -Body (@{ reason = "verified" } | ConvertTo-Json)
```

- alice approving her own request → **403** (4-eyes).
- **Correct payload / redirect:** add `targetTopic` and/or `payloadBase64` (base64 of the corrected
  message) to the maker body. The target must be the original topic or in `reliability.replay.allowed-topics`.
- **Reject** instead: `POST /api/approvals/{requestId}/reject`.
- **Return to maker** (rework): `POST /api/approvals/{requestId}/return` (checker — may attach a
  suggested `targetTopic`/`payloadBase64` + note). The maker then corrects via
  `POST /api/approvals/{requestId}/resubmit` (any operator → becomes the maker, request goes back to
  PENDING, revision bumped). List the maker's correction queue with `GET /api/approvals?status=RETURNED`.
- **Turn it off** (non-bank/dev): `RELIABILITY_APPROVAL_REQUIRED=false` → replays execute directly.

| Config | Default | Meaning |
| --- | --- | --- |
| `reliability.replay.approval-required` | `true` | require maker-checker approval |
| `reliability.replay.require-distinct-checker` | `true` | checker must differ from maker |
| `reliability.replay.allowed-topics` | (empty) | extra topics a replay may target |

Roles (under the `secure` profile): `OPERATOR` raises and resubmits requests; `APPROVER`
approves / rejects / returns them. The checker must differ from the (re)submitting maker.

---

## Profiles

| Profile | Auth | When to use |
| --- | --- | --- |
| `local` / *(none)* | **disabled** (permissive) + dev CORS | local development & testing |
| `secure` | OIDC resource server, role-based | production; needs `OIDC_ISSUER_URI` (see `application-secure.yml`) |

---

## Troubleshooting

**`Target file "\" not found` / `Missing expression after unary operator '--'` (PowerShell).**
`\` is a *bash* line-continuation; PowerShell doesn't understand it. Put the command on **one line**,
or use a backtick `` ` `` at the end of each line:
```powershell
flutter run -d chrome `
  --dart-define=API_BASE_URL=http://localhost:8080 `
  --dart-define=AUTH_MODE=dev
```

**`curl` fails in PowerShell — `A parameter cannot be found that matches parameter name 'X'`.**
In PowerShell `curl` is an alias for `Invoke-WebRequest`, which doesn't understand `-X`. Use real curl
as **`curl.exe`**, or PowerShell-native cmdlets. Also: send each command on its own line (don't paste
several `curl` lines at once).
```powershell
curl.exe -X POST "http://localhost:8081/send/storm?count=600"     # real curl (bundled with Win 10+)
Invoke-RestMethod -Method Post "http://localhost:8081/send/all"   # PowerShell-native POST
Invoke-RestMethod "http://localhost:8080/api/failures"            # PowerShell-native GET
```

**Sample producer fails with "topic ... does not exist" / `UnknownTopicOrPartition`.**
The backend hasn't provisioned the topics yet (auto-create is off). Start Kafka **and** the backend
first, then retry `curl -X POST http://localhost:8081/send/all`.

**Backend floods with `UNKNOWN_TOPIC_OR_PARTITION` / `Re-drive failed … Topic X not present`.**
A retried failure (or a manual replay) is being re-driven to its **original** business topic (e.g.
`payments.transactions`), which doesn't exist on the broker (auto-create is off). The sample now
creates its business topics on startup; or create them manually once:
```bash
docker exec erp-kafka bash -c 'for t in payments.transactions orders.events inventory.updates billing.debits sagas.events; do /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic $t --partitions 3 --replication-factor 1; done'
```

**Backend logs a burst of `dispatcherServlet … connection aborted` (broken pipe).**
Harmless: an SSE live-feed client (a browser tab, or `curl -N /api/stream`) disconnected while the
platform was pushing updates — common during a storm. Data is unaffected (the feed is best-effort).
The backend now silences this; pull + restart to pick up the change.

**Frontend (Chrome) shows network/CORS errors.**
Make sure the backend is running in the default/`local` profile (not `secure`) — dev CORS is only
enabled there. Alternatively run the desktop target (`-d windows`), which isn't subject to browser CORS.

**Port already in use (8080 / 8081 / 9092).**
Something else is bound. Stop it, or override: backend `SERVER_PORT=8090 mvn spring-boot:run`,
sample `SERVER_PORT=8091 mvn spring-boot:run`.

**`flutter` not recognized / wrong Dart version.**
Install/upgrade the Flutter SDK and re-run `flutter doctor`. The console needs Dart ≥ 3.4.

**Want a clean slate.** `docker compose down -v` wipes broker data so the platform re-provisions topics
and state from scratch on the next backend start.

---

## One-time vs every-time

- **One-time:** install prerequisites; `flutter create .` + `flutter pub get` in `frontend/`.
- **Every time:** `docker compose up -d` → backend → sample → frontend.
