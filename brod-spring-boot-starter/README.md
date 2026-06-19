# brod-spring-boot-starter

Two-line onboarding for the **Event Reliability Platform**. It fixes the two problems every team hits
when adopting a DLQ by hand:

- **No more silent loss.** When a consumer exhausts its retries, Spring Kafka's default behaviour is to
  log-and-skip — the failed (financial) event is gone with no record. This starter swaps in a recoverer
  that **publishes the exhausted record to Brod's inbound DLQ** with the failure-header contract stamped,
  so Brod can classify, retry and surface it. Drop → preserve.
- **Safe retries/replays.** An `IdempotencyStore` lets your handler dedup on the correlation/transaction
  id, so a re-delivered or re-driven message never re-runs a side effect (the double-transaction risk).

## Add it (2 lines)

```xml
<dependency>
  <groupId>com.brod</groupId>
  <artifactId>brod-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>
```

```yaml
spring.kafka.bootstrap-servers: ${KAFKA:localhost:9092}
# brod.dlq-topic defaults to reliability.dlq.inbound
```

That's it — exhausted retries now land in Brod automatically. The retry policy is
`brod.retries` (default 2) attempts, `brod.backoff-ms` (default 1000) apart.

## Configuration (`brod.*`)

| Property          | Default                   | Meaning                                         |
|-------------------|---------------------------|-------------------------------------------------|
| `brod.dlq-topic`  | `reliability.dlq.inbound` | Brod inbound DLQ to capture exhausted records   |
| `brod.retries`    | `2`                       | Re-attempts before sending to the DLQ           |
| `brod.backoff-ms` | `1000`                    | Delay between re-attempts                        |
| `brod.auto-dlq`   | `true`                    | Set `false` to opt out of the auto-DLQ handler  |

Defining your own `CommonErrorHandler` bean also disables the auto-DLQ handler (yours wins).

## Idempotent handlers

```java
@Component
class PaymentConsumer {
    private final IdempotencyStore idempotency; // injected

    @KafkaListener(topics = "payments")
    void onPayment(ConsumerRecord<String, Payment> rec) {
        String txnId = rec.key(); // your correlation/transaction id
        if (!idempotency.markProcessed(txnId)) {
            return; // duplicate — skip the side effect
        }
        // ... apply the debit/credit exactly once ...
    }
}
```

The default `InMemoryIdempotencyStore` is process-local. For production use the built-in
**Aerospike-backed** store, so dedup spans instances and restarts with sub-millisecond lookups and a
self-expiring dedup window:

```xml
<!-- add alongside the starter -->
<dependency>
  <groupId>com.aerospike</groupId>
  <artifactId>aerospike-client-jdk21</artifactId>
  <version>9.0.5</version>
</dependency>
```

```yaml
brod:
  idempotency:
    store: aerospike
    aerospike:
      hosts: aerospike1:3000,aerospike2:3000   # host:port (port defaults to 3000)
      namespace: brod
      set: idempotency
      ttl-seconds: 604800                       # dedup window (0 = namespace default, -1 = never expire)
```

`markProcessed` is an atomic create-only write: the first occurrence succeeds; a duplicate returns
`false` (Aerospike `KEY_EXISTS_ERROR`). If your app already exposes an `IAerospikeClient` bean it is
reused; otherwise one is created from the config above. To plug in a different store (Redis, JDBC, …),
just define your own `IdempotencyStore` bean — it takes precedence over both built-ins.

## Why a starter (not the backend jar)

Owning apps adopt **only the header contract** — never a dependency on Brod's backend. This starter
carries its own copy of the contract (`BrodHeaders`) and depends solely on `spring-kafka`, so it stays
a thin, safe library to roll out org-wide as a compliance control.
