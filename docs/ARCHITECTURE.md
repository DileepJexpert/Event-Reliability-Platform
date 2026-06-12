# Architecture

The platform is **one Spring Boot monolith** whose internal modules are Java packages (not
services), deployed as **N replicas in a single Kafka consumer group**. Kafka is the substrate for
transport, storage (compacted topics), scheduling (tiered retry topics + pause/seek) and the audit
log. There is no database.

## Modules (Java packages under `com.eventreliability`)

| Package | Responsibility |
| --- | --- |
| `config` | `ReliabilityProperties` (all `reliability.*` config), `TopicNames`, the AdminClient `TopicProvisioner`, `KafkaConfig`. |
| `domain` | Taxonomy, lifecycle states, the failure-header contract, `FailureRecord`, `AuditEvent`/`AuditTimeline`, `Incident`, `ControlCommand`, `RootCauseSignature`. |
| `ingestion` | Consume inbound failures; write `RECEIVED` state + audit; forward to async classification. `FailureRecordFactory` maps the header contract to a record. |
| `classification` | Externalised rule table (`ClassificationProperties`), the `Classifier`, and the async `ClassificationListener`. |
| `routing` | `RoutingService` (dispatch by taxonomy) and `ParkingService` (business/quarantine/park/exhausted terminal lanes). |
| `retry` | `RetryScheduler` (tier escalation), `DelayedRetryListener` (pause/seek delay loop), `RetryRedriveService` (re-drive to source). |
| `streams` | `StreamsTopology` (GlobalKTable read models), `PatternDetectionTopology` (windowed incident detection), `ReadModels` (queries). |
| `state` / `audit` | `StateService` (compacted system of record), `AuditService` (append-only audit log). |
| `control` | `ControlCommandService` (issue commands), `ControlCommandListener` + `ReplayService` (execute). |
| `observability` | `PlatformMetrics` (Micrometer), `SseService` + `LiveFeedListeners` (live feed), `IncidentNotifier`. |
| `housekeeping` | `StaleCaseSweeper` — the only `@Scheduled`, idempotent. |
| `api` / `security` | REST controllers + SSE + error handling; OIDC resource server + roles. |

## The two runtime flows

### Per-message resolution (§7.1)

```
inbound ─▶ Ingestion: state=RECEIVED, audit, forward ─▶ classify(async)
        ─▶ Classifier (rule table) ─▶ state=CLASSIFIED, audit ─▶ Routing:
             TRANSIENT/INFRASTRUCTURE ─▶ RetryScheduler ─▶ retry.<tier> (x-eligible-at)
                                          ─▶ DelayedRetryListener (pause/seek until eligible)
                                          ─▶ re-drive to source (x-attempt-count+1), state=RETRYING
                                          ─▶ (re-fails ⇒ re-enters, next tier;  exhausts ⇒ parked)
                                          ─▶ (no re-failure within grace ⇒ housekeeping ⇒ RESOLVED)
             BUSINESS ─▶ business.routed (reason), state=ROUTED_BUSINESS
             POISON   ─▶ parked, state=QUARANTINED_POISON
             UNKNOWN  ─▶ parked, state=PARKED_UNKNOWN
```

Every transition appends to the append-only audit log.

### Pattern detection (§7.2, continuous & independent)

A Kafka Streams topology reads the same inbound stream, re-keys each record to its **root-cause
signature** (exception type ⊕ source topic ⊕ optional schema-version hint — header access via the
Processor API), counts per signature in **tumbling windows**, and emits an `Incident` when a count
crosses the threshold. Incidents are written keyed by `signature:windowStart` to the compacted
`views.incidents` view (re-emits collapse to one record) and to the `incidents` live feed.

## Lifecycle state machine (§6.2)

```
RECEIVED → CLASSIFIED → (RETRY_SCHEDULED → RETRYING)* → terminal
terminal: RESOLVED | EXHAUSTED_PARKED | ROUTED_BUSINESS | QUARANTINED_POISON | PARKED_UNKNOWN
control:  REPLAY_REQUESTED → REPLAYED ;  BULK_REPLAY_REQUESTED → BULK_REPLAY_APPROVED
```

**Why a housekeeping sweep promotes RESOLVED:** onboarded apps adopt only the failure-header contract,
not a success callback, so the platform cannot directly observe a successful re-drive. A re-driven
message that does not come back as a new failure within the resolve-grace is presumed `RESOLVED`. The
sweep is idempotent, so running it on every instance is harmless.

## State & storage (no database, §9)

* **System of record** — the compacted `reliability.state` topic. The latest `FailureRecord` per
  correlation id survives compaction, so the topic *is* current state. The opaque payload (base64) and
  original headers are retained on the record so the platform can faithfully re-drive/replay without
  an external store.
* **Read models** — Kafka Streams **GlobalKTables** give every instance a full local copy, so any
  console query is answered locally with **no inter-instance RPC**:
  * `state` → current failures (list / filter / detail / parked-by-state).
  * `views.audit` → per-correlation-id audit timeline (aggregated from the append-only audit log).
  * `views.incidents` → active incidents.
* **TTL** — compacted state has no built-in "forget"; the housekeeping sweep tombstones
  terminal-and-aged records.

> Parked messages are served by filtering the `state` GlobalKTable on the parked states rather than a
> separate view: Kafka Streams forbids registering the `state` topic as a second source alongside the
> failures global table, and the global table already provides the required full-local reads.

## Tiered retry mechanics (§10)

* One topic per tier (`reliability.retry.<name>`) so a stuck message never head-of-line-blocks others.
* The **platform owns the attempt count.** Tier = `attemptCount − 1`; each re-failure escalates to the
  next tier; once tiers are exhausted the message is parked. The re-drive increments
  `x-attempt-count`; the owning app echoes it back on the next failure.
* **Delay = pause/seek, not sleep.** When `now < x-eligible-at` the listener calls
  `Acknowledgment.nack(Duration)` (bounded by `reliability.retry.max-pause`): the container pauses the
  partition and re-seeks while continuing to `poll()`, so the consumer stays in the group and never
  breaches `max.poll.interval`. Timing is decided only by the partition-owning consumer.

## Control plane (§13)

Console actions audit the request (attributing the OPERATOR — the human approval gate) and publish a
`ControlCommand` to `reliability.control.commands`. Consuming commands from Kafka means exactly one
instance (the partition owner) executes each replay, so mutations are HA and ordered. Replay
reconstructs the original message from retained state and republishes to the source topic. Bulk replay
recovers the whole cohort sharing an incident's signature and marks the incident resolved.

## Schema-drift inference without a registry (§12)

Drift is inferred by **symptom**: deserialization/mapping exceptions classify as `POISON`, and the
root-cause signature folds in the optional `x-schema-version` header, so a burst of
`DeserializationException@orders.v2` reads as one incident — sufficient for "a field disappeared at
14:03" alerts, though not authoritative contract validation. An accepted trade-off.

## Known trade-offs

* **GlobalKTable memory** — every instance holds a full local copy of state/audit/incident views. The
  spec explicitly chooses this for RPC-free reads; for very large state, tombstone TTL mitigates growth
  and interactive-query RPC could be reserved for data too large to replicate.
* **Presumed-resolved** — without a success callback, RESOLVED is inferred after a grace period.
* **Re-drive key** — the base header contract carries no original key; an optional `x-original-key`
  header preserves source-topic partitioning when ordering matters.
* **LLM classification** is a future hook behind the same async topic, not part of this build (§11).
