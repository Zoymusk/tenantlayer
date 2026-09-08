# Kafka

A message is the hardest place to lose a tenant, and the worst place to lose one. A consumer
thread is long-lived and pooled: the failure is not that the tenant is missing, it is that
the *previous* record's tenant is still bound. That turns a lost read into a cross-tenant
**write**, which no policy can undo afterwards.

## What happens automatically

Add `spring-kafka` and nothing else. TenantLayer registers a producer interceptor and a
record interceptor, and Boot picks both up.

```java
@Service
public class OrderEvents {

    private final KafkaTemplate<String, Order> kafka;

    public void publish(Order order) {
        // The bound tenant is written into a record header.
        kafka.send("orders", order.getId().toString(), order);
    }

    @KafkaListener(topics = "orders")
    public void handle(Order order) {
        // That tenant is bound before this line, and unbound after it.
        fulfilment.schedule(order);
    }
}
```

Neither method mentions a tenant. The producer takes it from the context; the consumer
restores it from the header.

## The header

```
tenantlayer-tenant: acme
```

Read it directly when you need to — a dead-letter handler, a log line, a metric:

```java
String tenant = TenantHeaders.read(record.headers());
```

It is a record header rather than part of the payload deliberately. Your message schema
stays yours, other consumers ignore a header they do not know, and a tenant is routing
metadata rather than business data.

## Batch listeners: you must scope each record

A batch can span tenants, so there is no single tenant to bind for the batch — and binding
the first record's would be worse than binding none, because the rest would be processed as
that tenant.

```java
@KafkaListener(topics = "orders", batch = "true")
public void handleBatch(List<ConsumerRecord<String, Order>> records) {
    for (ConsumerRecord<String, Order> record : records) {
        TenantKafka.runAsRecordTenant(record, () -> fulfilment.schedule(record.value()));
    }
}
```

Returning a value per record:

```java
List<Receipt> receipts = records.stream()
        .map(record -> TenantKafka.callAsRecordTenant(record, () -> fulfilment.schedule(record.value())))
        .toList();
```

A record carrying no tenant header is **refused** rather than processed as whoever came
before it. That is the whole reason this is explicit rather than automatic.

## Messages produced outside a request

A job that publishes without a request behind it has no tenant bound, so the record gets no
header — and the consumer will refuse it. Bind one first:

```java
@Scheduled(cron = "0 0 * * * *")
void publishHourlyRollups() {
    tenantTasks.forEachTenant(tenantId -> kafka.send("rollups", rollups.build()));
}
```

Each send happens with that tenant bound, so each record carries the right header.

## Consuming from a service that does not use TenantLayer

The header is plain text — anything can write it:

```java
ProducerRecord<String, Order> record = new ProducerRecord<>("orders", key, order);
TenantHeaders.write(record.headers(), "acme");
producer.send(record);
```

If the upstream cannot be changed, resolve the tenant yourself and scope the work:

```java
@KafkaListener(topics = "legacy-orders")
public void handle(ConsumerRecord<String, LegacyOrder> record) {
    String tenantId = record.value().getAccountCode();   // wherever it really lives

    TenantContext.runWithTenant(TenantScope.of(tenantId),
            () -> fulfilment.schedule(convert(record.value())));
}
```

## Testing it

The test that matters is the retained-tenant one, and it needs two records in a row:

```java
@Test
void aRecordWithoutATenantIsNotProcessedAsThePreviousOne() {
    send("orders", withTenant("acme", order("laptop")));
    awaitProcessed();

    send("orders", withoutTenant(order("monitor")));

    // The second record must be refused, not silently handled as acme.
    assertThat(processedTenants()).containsExactly("acme");
    assertThat(ordersFor("acme")).extracting(Order::item).containsExactly("laptop");
}
```

Make it fail first: bind the tenant outside the loop in a batch listener, and confirm the
second record is processed as the first record's tenant. If the test still passes, it is
not exercising the boundary.

## What is not handled

**Kafka Streams.** Topology processing does not go through the listener interceptors. Read
the header yourself with `TenantHeaders.read` and scope with `TenantContext.runWithTenant`.

**Consumers that commit outside the listener.** If you manage offsets manually and process
records after the listener returns, the tenant is already unbound — scope the work with
`TenantKafka.runAsRecordTenant` where it actually runs.

See [context propagation](context-propagation.md) for the other boundaries, and
[recipes](recipes.md#process-a-kafka-topic-per-tenant) for this in a complete service.
