# Event Reliability Platform ("Brod") — Roadmap toward a Central Error Handler

This document captures candidate features for evolving Brod from a Kafka DLQ triage tool into a
**central error handler for the whole organisation** — and, with an HTTP intake, an *all*-error
handler. It records, for each feature, **how it would work** (within the hard constraints) and
**where it is useful** in a multi-team bank (digital lending, CRM, asset, liability, payments).

> Status: **discussion / not yet implemented.** Nothing here changes behaviour; it's a prioritisation aid.

## Hard constraints these must respect
Monolith (one backend JAR + one Flutter app) · plain Apache Kafka only (no Confluent) · **no external
database** (state in compacted Kafka topics + Kafka Streams) · no external observability/alerting/UI
tooling (Micrometer/Actuator + Flutter). Outbound **webhooks** (Slack/Teams) are fine; an outbound
**LLM call** or **SMTP** bends the "self-hosted" stance and must be opt-in.

## What already exists (the core)
Multi-DLQ ingestion → classification (TRANSIENT/INFRASTRUCTURE/BUSINESS/POISON/UNKNOWN) → tiered retry
(5s→1m→5m→30m) → park / business-route → pattern detection → incidents → **maker-checker (4-eyes)**
replay with payload correction → audit log → console (dashboard, failures search by source/DLQ topic &
app with type-ahead, incidents, approvals, audit) → correlation-id propagation → per-failure DLQ-topic
tracking.

---

## Theme A — Make adoption effortless (turns "a tool" into "the hub")

### A1. `brod-spring-boot-starter` (onboarding library)
- **How:** A Maven dependency + Spring auto-config. A service sets two properties
  (`brod.dlq-topic`, `brod.source-app`); the starter registers a Kafka `DefaultErrorHandler` +
  `DeadLetterPublishingRecoverer` that, after the consumer's own retries, forwards the failed record to
  the team's DLQ with the full contract (`correlationId` from MDC/header, `x-original-topic`, exception,
  `x-source-app`, `x-schema-version`). No hand-written forwarding code.
- **Where useful:** With dozens of services across 5 domains, hand-rolling the contract is inconsistent
  and error-prone. The starter makes onboarding a 2-line PR and guarantees `correlationId` propagation
  everywhere. **This is the single biggest "central" enabler — without it, org-wide adoption stalls.**
- **Effort:** Medium. **Constraints:** fully compatible.

### A2. HTTP intake `POST /api/failures` (the "all-error" part)
- **How:** A REST endpoint accepts a failure JSON and publishes it into the same DLQ pipeline; reuses
  classification/retry/park unchanged.
- **Where useful:** Captures non-Kafka failures — EOD batch/settlement, NACH/ACH file processing, REST
  calls to payment gateways, schedulers — so the platform is truly *central*, not Kafka-only.
- **Open question:** replay semantics for non-Kafka sources (re-publish to a Kafka topic vs call a
  configured reprocess callback vs "mark for manual reprocess"). Visibility/triage work regardless.
- **Effort:** Low. **Constraints:** compatible.

---

## Theme B — Close the loop to the right people (biggest multi-team gap)

### B1. Ownership registry (topic/app → team)
- **How:** A compacted Kafka topic mapping `source-topic`/`source-app` → owning team + contact
  (Slack/Teams webhook). Editable via console or config (no DB). Everything else resolves owners from it.
- **Where useful:** A dashboard alone doesn't assign accountability. The registry lets failures and
  incidents be attributed and routed per domain. It is the backbone for per-team notifications and
  per-team scoped views/RBAC.
- **Effort:** Medium. **Constraints:** compatible (compacted topic).

### B2. Per-team notifications (Slack/Teams webhooks)
- **How:** Extend the existing notifier (log/webhook) to resolve the owner from the registry and POST to
  that team's channel; add severity (incident = high, single park = low), dedup (a storm ≠ thousands of
  messages), quiet hours.
- **Where useful:** Closes the loop — no one has to *watch* the console. A schema change floods
  `payments.transactions` → incident raised → payments on-call gets a Slack ping with root cause + a deep
  link; CRM is not bothered.
- **Effort:** Medium. **Constraints:** Slack/Teams = outbound webhooks (OK); email needs SMTP (flag).

### B3. SLA / aging alerts
- **How:** The existing housekeeping sweep computes the age of non-terminal/parked records and emits an
  alert when age > a per-domain threshold.
- **Where useful:** Regulatory/ops SLAs ("no payment failure unactioned > 30 min"; "surface anything
  parked > 4h"); catches forgotten items that banks get audited on.
- **Effort:** Medium. **Constraints:** compatible.

---

## Theme C — Reduce manual triage

### C1. LLM-assisted classification (UNKNOWN long tail)
- **How:** When rules return UNKNOWN, the already-decoupled async classifier optionally asks an LLM to
  classify + suggest action/reason from the **exception + message + stacktrace only** (never the
  payload). Cache by root-cause signature to bound cost.
- **Where useful:** UNKNOWN is where humans burn time; auto-triaging (or suggesting) the long tail is a
  big saver and a strong demo moment.
- **Effort:** Medium. **⚠️ Constraint:** outbound LLM call bends the self-hosted stance — keep opt-in,
  send no payload, or point at a self-hosted model. Data-egress review needed for a bank.

### C2. Classification rules editor in the console
- **How:** Rules in a compacted topic, edited in-UI with hot reload of the classifier.
- **Where useful:** Ops add rules as new error types appear, no redeploy; each domain can add its own.
- **Effort:** Medium. **Constraints:** compatible.

### C3. Fix-and-replay templates
- **How:** Save a payload-correction (e.g. "set currency=INR when missing") as a reusable template;
  apply on bulk replay.
- **Where useful:** Recurring data bugs — fix hundreds of messages with one template instead of editing
  each.
- **Effort:** Medium. **Constraints:** compatible.

---

## Theme D — Trends, analytics & governance (bank-grade)

### D1. Trends dashboard
- **How:** Kafka Streams windowed aggregations (same pattern as incident detection) → materialised views
  → a Trends tab. No DB.
- **Where useful:** Failures/day, MTTR, resolution rate, top failing topics/apps, recurring root causes —
  per-team scorecards, capacity planning, and ROI evidence ("MTTR down from hours to minutes").
- **Effort:** Medium. **Constraints:** compatible.

### D2. PII masking / retention (governance)
- **How:** Configurable field redaction (JSONPath/regex) applied before a payload is returned to the
  console; role-gated reveal; per-domain retention.
- **Where useful:** Banks legally cannot show raw account numbers/PII to every operator — effectively a
  **prerequisite to production**. Per-domain retention satisfies compliance.
- **Effort:** Low–Medium. **Constraints:** compatible.

### D3. Bulk replay by filter (+ scheduled)
- **How:** Extend bulk-replay (currently per-incident) to take a query (topic/app/time/classification),
  back-pressure guarded (already has `bulkBatchSize`).
- **Where useful:** After an upstream outage is fixed, replay everything from the affected window in one
  governed, audited action.
- **Effort:** Medium. **Constraints:** compatible.

---

## Theme E — Resilience & ops
- **E1. Re-drive rate-limit / circuit breaker** — don't hammer a still-down downstream; avoid retry
  storms at scale. (Medium)
- **E2. DLQ-depth / consumer-lag panel** (Kafka AdminClient) — is a team's DLQ growing? Is Brod keeping
  up? (Low)

---

## Sequencing & dependencies
```
A1 starter lib ─┐
A2 HTTP intake ─┴─► more failures flow in consistently
B1 ownership registry ─┬─► B2 per-team notifications
                       ├─► per-team scoped views / RBAC
                       └─► B3 SLA alerts
D1 trends dashboard      ← benefits from real volume (A/B)
C1 LLM / C2 rules / C3 templates ← reduce triage once volume is real
D2 PII masking           ← gate before any production rollout
```

## Recommendations by goal
| Goal | Build first |
|---|---|
| **Org-wide "central" adoption** | A1 (starter) → B1+B2 (ownership + notifications) |
| **Demo / showcase wow** | B2 (live Slack alert) + D1 (trend charts); C1 (LLM) if egress acceptable |
| **Production-readiness at a bank** | D2 (PII masking) first, then B3 (SLA) + E1 (rate-limit) |

---

## Decision needed
Pick a direction (adoption / demo / production) and the top feature, then we deep-dive a concrete design
(topics, DTOs, config, UI, tests) before implementing.
