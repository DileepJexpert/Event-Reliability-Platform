# Testing & Demo Guide — exercising every console screen

This is a hands-on walkthrough for **replicating each use case** behind every menu in the Flutter
console (Dashboard, Failures, Incidents, Trends, Approvals, Audit) plus the cross-cutting features
(HTTP intake, multi-team ownership, per-team alerts). Every step has a copy-pasteable command and what
you should see.

> All state lives in Kafka — there's no database. To start completely fresh, see [Reset](#reset).

---

## 0. Start everything (with the demo profile)

The **`demo` profile** lowers the incident threshold (5 failures / 2-min window instead of 500 / 5m),
turns on the notifier (log mode) and seeds example team ownership — so every screen lights up quickly.

```bash
# 1) Kafka broker (KRaft) on localhost:9092
docker compose up -d

# 2) Backend (platform) on :8080  — demo profile
cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=demo

# 3) Sample producer on :8081 (separate terminal)
cd samples/dlq-sample-producer && mvn spring-boot:run

# 4) Console (separate terminal)
cd frontend && flutter create . && flutter pub get && flutter run -d chrome
```

**Ports & endpoints**

| What | URL |
|---|---|
| Platform REST/SSE | `http://localhost:8080` |
| Sample producer | `http://localhost:8081` (GET `/` for help) |
| Health / metrics | `http://localhost:8080/actuator/health`, `/actuator/prometheus` |

**Windows PowerShell note:** `curl` is an alias for `Invoke-WebRequest`. Either use `curl.exe …` with
the commands below, or `Invoke-RestMethod -Method Post <url> -ContentType 'application/json' -Body '…'`.

**Maker-checker identities:** in the console top bar use the **Demo switcher** to flip between the maker
and the checker. Over curl, set the actor with the **`X-Actor`** header (any names; role checks only
apply under the `secure` profile, but the 4-eyes "checker ≠ maker" rule always applies).

---

## 1. Dashboard

**Purpose:** Live operations overview — KPI cards, breakdowns by classification / topic / app, an
active-incident banner, and a recent-failures table. Updates in real time over SSE.

**Replicate:**
```bash
# Produce one failure of each classification:
curl -X POST http://localhost:8081/send/all
```

**Verify:** the **live** dot stays green; "Total failures" and the breakdown bars increase within a
second or two (SSE push). The recent-failures table fills; click a row to open its detail.

---

## 2. Failures

**Purpose:** Searchable list of every failed message. A service owner self-serves "show my failures"
by filtering on source topic / DLQ topic / source app / status / classification, or jumps to a
correlation id. New **Team** column shows the owning team.

**Replicate:**
```bash
curl -X POST http://localhost:8081/send/transient
curl -X POST http://localhost:8081/send/business
curl -X POST http://localhost:8081/send/poison
```

**Verify / things to try:**
- Filter **Classification = POISON** → only poison messages.
- Type in **Source app** (type-ahead suggests known values from `/api/failures/facets`).
- Paste a correlation id into "Go to correlation id…" → opens detail directly.
- REST equivalent:
  ```bash
  curl "http://localhost:8080/api/failures?classification=POISON&size=20"
  ```

### 2a. Failure detail + maker actions (replay / quarantine)
Open any failure (click a row).

**Verify:** metadata, classification chips, **Owning team**, stacktrace and the full **audit timeline**.
As an **OPERATOR** you'll see **Request replay** (edit the payload / redirect topic) and **Quarantine** —
both raise maker-checker requests (see [Approvals](#5-approvals)).

---

## 3. Incidents

**Purpose:** Detect **systemic** failures — many messages failing with the *same root cause* in a
window — and recover the whole cohort with one approved **Bulk replay**.

**Replicate** (demo threshold = 5):
```bash
# 10 failures sharing one root cause:
curl -X POST "http://localhost:8081/send/storm?count=10"
```

**Verify:** within the 2-minute window an incident card appears (root cause, count, source topic). The
backend log also shows a per-team alert line: `[ALERT] INCIDENT … (owner: …)`. REST:
```bash
curl http://localhost:8080/api/incidents
```

### 3a. Bulk replay (maker-checker)
On the incident card click **Bulk replay** (maker) → approve as a **different** user in
[Approvals](#5-approvals). The whole cohort moves to `REPLAYED` and the incident flips to `RESOLVED`.
REST:
```bash
curl -X POST http://localhost:8080/api/incidents/<INCIDENT_ID>/bulk-replay \
  -H "X-Actor: alice" -H "Content-Type: application/json" -d '{"reason":"upstream fixed"}'
```

---

## 3b. Anomalies (adaptive detection)

**Purpose:** Catch failure-rate spikes **relative to each series' own baseline** — not a fixed global
threshold. An off-peak spike on a normally-quiet topic is caught, and a brand-new root cause appearing
in volume is flagged as **novelty**.

**Replicate** (a brand-new root cause appearing in volume — novelty against an empty baseline):
```bash
for i in $(seq 1 6); do
  curl -s -X POST http://localhost:8080/api/failures -H "Content-Type: application/json" \
    -d '{"source":"ledger.events","exceptionClass":"com.bank.NovelException","exceptionMessage":"new failure mode"}' >/dev/null
done
```

**Verify:** open **Anomalies** → an entry for `com.bank.NovelException` / `ledger.events` with its recent
count, baseline and σ score. REST: `curl http://localhost:8080/api/anomalies`. Tune with
`reliability.anomaly.bucket` / `lookback` / `min-count` / `sensitivity`.

---

## 4. Trends

**Purpose:** Analytics over the current failure set — totals, resolution rate, MTTR, a 14-day daily
chart, and breakdowns by classification / state / top topics / top apps.

**Replicate:**
```bash
curl -X POST http://localhost:8081/send/all
curl -X POST "http://localhost:8081/send/storm?count=10"
```

**Verify:** open **Trends** → KPI cards populate; the daily bar shows today's spike (hover a bar for the
count); breakdown panels list the top topics/apps. REST:
```bash
curl http://localhost:8080/api/trends
```

---

## 4a. Exposure (value at risk)

**Purpose:** How much money is tied up in stuck (un-recovered) failures — the figure a manager asks
for. **Read-only:** the platform extracts an amount + currency from each stuck failure's JSON payload and
sums it by currency / topic / team, plus the biggest single exposures. No raw payloads are shown.

**Replicate:**
```bash
curl -X POST http://localhost:8080/api/failures -H "Content-Type: application/json" \
  -d '{"source":"payments.events","exceptionClass":"com.bank.TimeoutException","payload":"{\"amount\":1000.50,\"currency\":\"USD\"}"}'
curl -X POST http://localhost:8080/api/failures -H "Content-Type: application/json" \
  -d '{"source":"orders.events","exceptionClass":"com.bank.SchemaException","payload":"{\"transactionAmount\":\"5000.00\",\"currency\":\"EUR\"}"}'
```

**Verify:** open **Exposure** → headline cards show `USD 1,000.50` and `EUR 5,000.00` at risk, with
breakdowns by topic/team and a "biggest stuck exposures" table. REST: `curl http://localhost:8080/api/exposure`.
Point `reliability.exposure.amount-fields` / `currency-fields` at the field names your events use.

---

## 5. Approvals (maker-checker / 4-eyes)

**Purpose:** Every mutating action (replay / bulk-replay / quarantine) is a **request** a *different*
user must approve — segregation of duties, fully audited.

**Full flow over REST:**
```bash
# 0) Create a parked failure and copy its correlationId from the response:
curl -X POST http://localhost:8081/send/unknown        # -> {"correlationId":"...", ...}

# 1) Maker raises a replay request -> 202 with a requestId:
curl -X POST http://localhost:8080/api/failures/<CORRELATION_ID>/replay \
  -H "X-Actor: alice" -H "Content-Type: application/json" -d '{"reason":"upstream fixed"}'

# 2) 4-eyes: the SAME user cannot approve -> 403:
curl -i -X POST http://localhost:8080/api/approvals/<REQUEST_ID>/approve \
  -H "X-Actor: alice" -H "Content-Type: application/json" -d '{"reason":"me"}'

# 3) A DIFFERENT checker approves -> 202, executes:
curl -X POST http://localhost:8080/api/approvals/<REQUEST_ID>/approve \
  -H "X-Actor: bob" -H "Content-Type: application/json" -d '{"reason":"verified"}'
```

**In the console:** open the parked failure → **Request replay** (as the maker via the Demo switcher) →
switch to the checker → **Approvals** menu → **Approve** (or **Return** to send it back for correction,
then the maker **Resubmits**). Lists: `GET /api/approvals?status=PENDING|RETURNED|ALL`.

**Verify:** the failure goes `PARKED_UNKNOWN → REPLAY_REQUESTED → REPLAY_APPROVED → REPLAYED`, and the
audit timeline records both **alice** (maker) and **bob** (checker).

---

## 6. Audit

**Purpose:** The immutable who/what/when history of a **single** message. It's a **lookup**, so it's
empty until you search a correlation id.

**Replicate:**
```bash
curl -X POST http://localhost:8081/send/transient     # copy the correlationId
```

**Verify:** **Audit** menu → paste the correlation id → **Search** → see `INGESTED`, `CLASSIFIED`,
retry/terminal entries. Do a replay/quarantine on it, then re-search to see the maker/checker actions.
REST (the timeline is part of the detail): `GET /api/failures/<CORRELATION_ID>`.

---

## 7. Cross-cutting use cases

### 7a. Classifications (what each sample does)
| Command | Classification | Expected outcome |
|---|---|---|
| `POST /send/transient` | TRANSIENT | retried (5s→1m→5m→30m), then presumed `RESOLVED` |
| `POST /send/infrastructure` | INFRASTRUCTURE | retried |
| `POST /send/business` | BUSINESS | `ROUTED_BUSINESS` (no retry) |
| `POST /send/poison` | POISON | `QUARANTINED_POISON` (no retry) |
| `POST /send/unknown` | UNKNOWN | `PARKED_UNKNOWN` (awaits human) |

### 7b. HTTP "all-error" intake (non-Kafka producers)
Report a failure straight over HTTP (batch jobs, REST integrations, file processors):
```bash
curl -X POST http://localhost:8080/api/failures -H "Content-Type: application/json" -d '{
  "source":"eod-settlement-batch","sourceApp":"settlement-service",
  "exceptionClass":"com.bank.batch.RecordRejectedException","exceptionMessage":"row 42 rejected",
  "payload":"{\"row\":42}"}'
```
**Verify:** returns `202` + a `http-…` correlation id; the failure appears on **Failures** and flows
through the same pipeline. (`source` + `exceptionClass` are required; omitting them returns `400`.)

### 7c. Multi-team ownership
With the demo profile's rules, send failures whose source matches a team:
```bash
curl -X POST http://localhost:8080/api/failures -H "Content-Type: application/json" \
  -d '{"source":"payments.events","sourceApp":"pay-svc","exceptionClass":"java.net.SocketTimeoutException","exceptionMessage":"timeout"}'
```
**Verify:** on **Failures** the **Team** column shows `Payments`; the detail shows **Owning team**.
Mapping: `GET /api/ownership` (channels are withheld).

### 7d. Per-team alert routing
Raise an incident on a team-owned topic, so the alert is attributed to that team:
```bash
for i in $(seq 1 6); do
  curl -s -X POST http://localhost:8080/api/failures -H "Content-Type: application/json" \
    -d '{"source":"payments.events","sourceApp":"pay-svc","exceptionClass":"com.bank.SchemaDriftException","exceptionMessage":"missing field"}' >/dev/null
done
```
**Verify:** an incident appears on **Incidents**, and the backend log shows
`[ALERT] INCIDENT … (owner: Payments) …`. To actually POST to Slack/Teams, add a `channel: <webhook>`
to the matching rule in `backend/src/main/resources/application-demo.yml` and restart.

### 7e. PII masking (payload protection)

Payloads containing PII (SSN, credit card, IBAN, email) are automatically masked in the API and
Flutter console. Replay re-drives the **original unmasked** payload.

```bash
# Send a failure whose payload contains PII:
curl -X POST http://localhost:8080/api/failures -H "Content-Type: application/json" -d '{
  "source":"customer.events","sourceApp":"onboarding-svc",
  "exceptionClass":"com.bank.ValidationException","exceptionMessage":"KYC check failed",
  "payload":"{\"name\":\"Jane Doe\",\"ssn\":\"123-45-6789\",\"email\":\"jane@bank.com\",\"card\":\"4111-1111-1111-1111\",\"iban\":\"GB29NWBK60161331926819\"}"}'
```

**Verify:** open the failure in the console — the **Payload** section shows the data with PII replaced:
- `123-45-6789` → `[MASKED:ssn]`
- `jane@bank.com` → `[MASKED:email]`
- `4111-1111-1111-1111` → `[MASKED:credit-card]`
- `GB29NWBK60161331926819` → `[MASKED:iban]`

Non-PII fields (`name`, `note`) appear unchanged. The "PII Protected" badge is shown next to the
Payload header.

REST equivalent:
```bash
curl http://localhost:8080/api/failures/<CORRELATION_ID> -H "X-Actor: alice" | python3 -m json.tool
# payloadBase64 → base64-decode it to see the masked JSON
```

**Encryption at rest** (production): set `RELIABILITY_ENCRYPTION_KEY` to a base64-encoded 256-bit key
and `RELIABILITY_ENCRYPTION_ENABLED=true`. Payloads are AES-256-GCM encrypted in the state topic;
the API still returns PII-masked text. Generate a key: `openssl rand -base64 32`.

### 7f. Ask Brod (AI operations assistant)
The **Ask Brod** menu is a **read-only** assistant: ask natural-language questions and get cited
answers grounded in the current incidents/failures (PII-masked before they reach the model). It needs a
self-hosted, OpenAI-compatible model endpoint, so it's off by default.

Enable it (point at your in-network model), then restart the backend:
```bash
export RELIABILITY_ASSISTANT_ENABLED=true
export RELIABILITY_ASSISTANT_URL=http://localhost:8000/v1   # your vLLM/TGI/Ollama/gateway
export RELIABILITY_ASSISTANT_MODEL=your-model
# optional, for an internal gateway: export RELIABILITY_ASSISTANT_API_KEY=...
```
Ask in the console (**Ask Brod** tab) or over REST:
```bash
curl -X POST http://localhost:8080/api/assistant/ask -H "Content-Type: application/json" \
  -H "X-Actor: alice" -d '{"question":"why are payments failing?"}'
```
**Verify:** the answer cites incident/failure ids, `grounded` is `true` and `contextSize` > 0. With the
assistant disabled you get a friendly "not configured" reply (`grounded:false`). Every query is audited
(acting user + question). The model only ever sees **PII-masked** context.

---

## Reset

State is compacted Kafka topics, so to wipe everything and start clean:
```bash
docker compose down -v && docker compose up -d   # -v drops the broker volume
# then restart the backend; it re-provisions empty topics on startup
```

---

## One-shot smoke test (fills every screen)
```bash
curl -X POST http://localhost:8081/send/all                 # Dashboard, Failures, Trends, Audit
curl -X POST "http://localhost:8081/send/storm?count=10"    # Incidents + per-team alert (log)
# then in the console: open a parked failure -> Request replay -> switch user -> Approvals -> Approve
```
