# Platform Architecture — the bank-scale framework

> **Status:** proposal / target architecture. This is the "where we're going" companion to
> [`ARCHITECTURE.md`](ARCHITECTURE.md), which documents Brod **as built today** (a single Kafka-only
> monolith with no database). This document describes how Brod grows into an
> **enterprise Kafka reliability & governance framework for a bank** — and deliberately relaxes the
> original "no external database / monolith-only" constraints where scale and compliance require it.

---

## 1. The problem we're actually solving

From real deployment feedback, a bank's Kafka estate has four chronic problems that **no existing tool
fixes**:

| Problem | What happens today | Consequence |
| --- | --- | --- |
| **Silent loss** | Teams retry N times, then *manually acknowledge* (commit + drop) | Failed **financial events vanish with no record** — an audit/completeness defect |
| **No DLQ adoption** | Producing to a DLQ with a contract is per-team work nobody does | The recovery/observability layer is starved of data |
| **Double transactions** | Non-idempotent consumers re-run side effects on retry | Double debit/charge — or, to avoid it, teams drop events (see *silent loss*) |
| **No business lens** | Grafana/Kibana/Jaeger/Kafka-UIs see infra signals & raw bytes | Nobody can answer "how much money is stuck?" or "did every transaction complete?" |

Observability tools (**Grafana, Kibana, Jaeger**) and Kafka UIs (**AKHQ, Redpanda Console, Conduktor**)
are all **detection or raw-browsing**. None understand a failure as a **business event** with lifecycle,
ownership, financial value, and an audit trail. That gap is the moat.

## 2. Design principles

1. **Fix reliability at the source.** A mandated client framework makes every app idempotent, auto-DLQ,
   and contract-compliant — so silent loss and double-transactions stop at the root, not after the fact.
2. **Observe, don't mutate.** The control plane is **read-only / additive** to production. Capturing a
   failed event to a *new* DLQ topic is preservation, not mutation. Recovery never blindly writes to prod.
3. **Recover via work-orders, not blind replay.** Where replay is unsafe (non-idempotent consumers), the
   platform emits the exact cohort to reprocess; the **owning team** runs it through *their own* controls.
4. **Compliance is a feature, not a report.** Audit, reconciliation, retention and PII handling are
   first-class, because that is what a bank actually buys.

## 3. The three layers

```
┌──────────────────────────────────────────────────────────────┐
│  Console (Flutter)  — ops · governance · compliance reporting │
├──────────────────────────────────────────────────────────────┤
│  CONTROL PLANE (Spring Boot)        ← read-only / additive    │
│  capture · classify · reconcile · duplicate-detect · incidents│
│  financial exposure · SLA/aging · audit · RBAC · reporting    │
│  governed recovery (work-orders, maker-checker)               │
├──────────────────────────────────────────────────────────────┤
│  CLIENT FRAMEWORK  (brod-starter)   ← used by EVERY bank app  │
│  idempotent producer · dedup consumer · auto-DLQ · retries ·  │
│  correlation/headers · schema validation · metrics/tracing    │
├──────────────────────────────────────────────────────────────┤
│  DATA: Kafka (transport/log) · Aerospike (dedup/hot serving)  │
│        Postgres (cases/audit/reporting) · S3 (cold archive)   │
└──────────────────────────────────────────────────────────────┘
```

The **client framework is the linchpin.** It feeds the control plane and eliminates the two root-cause
problems (silent loss, duplicates). Build it first; without it the control plane has no reliable input.

## 4. Datastore strategy

Use the right store for each job — this is where the relaxed "no DB" rule pays off.

| Store | Job | Why |
| --- | --- | --- |
| **Kafka** | Event transport, append-only log, audit topic, compacted views | The backbone; unchanged |
| **Aerospike** | **Idempotency/dedup store** + hot read-models | Sub-ms lookups over billions of txn-ids at high TPS, native TTL for dedup windows — its proven niche in finance |
| **PostgreSQL** | Cases (failures/incidents/approvals), audit, **compliance reporting**, search, RBAC, config | ACID, rich SQL/joins, full-text search — what Kafka can't do |
| **S3 (optional)** | Cold archive of payloads/audit for multi-year retention | Far cheaper than Kafka/Aerospike for regulatory cold data |

**Sequencing the stores (avoid premature complexity):**
- **Start with Postgres only.** It covers cases, audit, reporting, *and* dedup at moderate volume.
- **Add Aerospike** when the dedup hot path genuinely needs sub-millisecond lookups at high TPS.
- **Add S3** when retention windows exceed what's economical in Kafka/Postgres.

## 5. Layer 1 — the client framework (`brod-starter`)

A thin Spring Boot starter every app adds (2 lines). It does **not** reinvent the Kafka consumer; it
configures Spring Kafka correctly and adds the bank's standards.

- **Idempotent producer** — transactional / outbox pattern for atomic "DB write + publish".
- **Dedup consumer** — checks `correlation-id` / `transaction-id` against the dedup store (Postgres →
  Aerospike) *before* applying side effects. This is the real fix for double-transactions.
- **Auto-DLQ** — swaps Spring Kafka's default (log-and-skip) recoverer for one that publishes the
  exhausted-retry record to the DLQ with the `FailureHeaders` contract stamped. **Drop → preserve.**
- **Correlation propagation**, tiered retry/backoff, **schema validation** (Apicurio, not Confluent),
  auto Micrometer metrics + tracing spans.
- **Fallback for zero-code teams:** a log-appender module that ships ERROR logs to the HTTP intake —
  detection-only (no payload), but better than silent loss.

> The framework is **mandate-able as a compliance control** ("no team may silently drop a financial
> event") — which is how you get org-wide adoption top-down instead of per-team goodwill.

## 6. Layer 2 — the control plane (Brod, expanded)

Everything Brod does today, plus the bank intelligence:

- **Capture & classify** (today) · **root-cause grouping → incidents** (today) · **team ownership** (today)
- **Reconciliation / completeness** — events in vs events completed; surface the gap
- **Duplicate detection** — flag likely double-processing (turns the team's fear into a feature)
- **Financial exposure** — sum business amounts of stuck events → "value at risk" KPI
- **SLA / aging register** — backlog by age and team, SLA-breach list
- **Governed recovery** — **work-orders + maker-checker**; replay only where idempotency is proven
- **Audit & compliance reporting** — immutable trail + regulator-ready export

All of the above is **read-only with respect to production**.

## 7. Layer 3 — the console

The existing Flutter app, extended with: exposure & reconciliation dashboards, compliance export,
per-team scorecards, and the governed work-order workflow.

## 8. Build vs. adopt — scope discipline

A full platform competes with Confluent + Conduktor (hundreds of engineer-years). **Build only the
differentiated layer; adopt OSS for commodity.**

| Build in-house (the moat) | Adopt OSS (commodity) |
| --- | --- |
| Client framework (encodes *your* idempotency/compliance standards) | Kafka; a Kafka UI for raw browsing |
| Reliability/recovery control plane | Schema registry → **Apicurio** (Apache-licensed, no Confluent) |
| Bank intelligence: exposure, reconciliation, compliance reporting | Metrics/dashboards → **Prometheus/Grafana** |
| Maker-checker, audit, governed recovery | Tracing → **Jaeger / OpenTelemetry** |

## 9. Phased roadmap

Each phase ships standalone value and answers a specific objection, in order.

| Phase | Deliverable | Value / objection answered |
| --- | --- | --- |
| **0 — Framework** | `brod-starter`: auto-DLQ + idempotency/dedup (Postgres-backed first) | Stops **silent loss** & **double-transactions** at the source |
| **1 — Capture & visibility** | Control plane ingests DLQs, classifies, attributes, dashboards; Postgres for search/audit | Turns "teams don't DLQ" into captured, visible data |
| **2 — Intelligence** | Reconciliation, duplicate detection, financial exposure, SLA/aging | The differentiated, **read-only** value (answers "no replay") |
| **3 — Governance** | Schema contracts, topic self-service, RBAC, compliance export, retention | Enterprise governance & regulatory reporting |
| **4 — Governed recovery** | Work-orders + maker-checker; replay where idempotency proven | Recovery **without** mutating prod blindly |

**Why this order:** it delivers **compliance value first** (capture = stop silent loss, and it never
mutates prod) and defers risky recovery until idempotency exists — matching the bank's risk posture.

## 10. How idempotency makes retry/replay safe

Retrying a **non-idempotent** consumer can double a transaction — this is real (Kafka is at-least-once;
partial-success-then-timeout re-runs the side effect). The fix is **idempotency, not abstinence**
("never retry" trades a *detectable* duplicate for an *undetectable* lost payment).

- The `correlation-id` / `transaction-id` becomes the **idempotency key**.
- The dedup consumer records applied keys (Postgres/Aerospike) and skips duplicates.
- ⚠️ Kafka **exactly-once (EOS) only covers Kafka→Kafka**. Any external side effect (core banking, a
  payment gateway, a DB) needs **application-level idempotency keys** — EOS does not extend there.
- Once a consumer is idempotent, Brod's re-drive (which preserves key + correlation-id) is safe.

## 11. What this is NOT

- **Not a Kafka proxy** (Conduktor Gateway). The platform stays *beside* Kafka, off the hot path — no
  inline latency, no single point of failure in the data path.
- **Not a replacement for observability.** It feeds Prometheus/Grafana and complements Jaeger/Kibana;
  it adds the failure-intelligence they lack.
- **Not a blind auto-replayer.** Recovery is governed, idempotency-gated, and team-executed.
