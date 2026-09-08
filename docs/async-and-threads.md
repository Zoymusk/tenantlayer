# Async, threads and scheduling

The tenant lives in a `ThreadLocal`. Every time work moves to another thread it has to be
carried across, and when it is not, **nothing throws** — the work runs untenanted, queries
return nothing, and you ship an empty report rather than an error.

This page is every thread boundary, what happens by default, and what you have to do.

| Boundary | Handled for you? |
|---|---|
| `@Async` on a Spring executor | Yes |
| Virtual threads (`spring.threads.virtual.enabled`) | Yes |
| `@Scheduled` | No — there is no request to take a tenant from |
| `CompletableFuture` with no executor | No — the common ForkJoinPool is not Spring's |
| Your own `ExecutorService` | No — until you wrap it |
| Parallel streams | No |

## @Async

```java
@Service
public class ReportService {

    @Async
    public CompletableFuture<Report> generate(String month) {
        // Runs on a pool thread with the calling request's tenant still bound.
        return CompletableFuture.completedFuture(builder.build(month));
    }
}
```

TenantLayer registers a `TaskDecorator` that captures the tenant when the task is submitted
and restores it on the worker, then puts the worker's previous scope back. Nothing to
configure.

### Virtual threads deserve a note

```properties
spring.threads.virtual.enabled=true
```

This one property, widely recommended and with no mention of tenancy anywhere near it,
makes Boot build a `SimpleAsyncTaskExecutor` instead of a `ThreadPoolTaskExecutor`. A
decorator registered only for the pooled one disappears with it — and every `@Async` method
starts running with no tenant.

TenantLayer registers both decorators, so turning virtual threads on changes nothing about
isolation. This was a real bug found while building the feature, and it is
[written up here](https://tenantlayer.io/blog/virtual-threads-broke-tenant-isolation).

## CompletableFuture

`supplyAsync` with no executor runs on the common `ForkJoinPool`, which Spring has never
heard of and cannot decorate:

```java
// Wrong — runs with no tenant, returns nothing, throws nothing.
CompletableFuture.supplyAsync(() -> reports.build());

// Right — the tenant is captured at the call site.
CompletableFuture.supplyAsync(TenantExecutors.supplier(() -> reports.build()));
```

Three names rather than three overloads of one — `runnable`, `callable`, `supplier` —
because a method reference like `this::loadReport` satisfies both `Callable` and `Supplier`
and an overloaded `capture(...)` would be ambiguous at exactly the call sites people write.

```java
TenantExecutors.runnable(() -> audit.record(event));
TenantExecutors.callable(() -> reports.build());
TenantExecutors.supplier(() -> reports.build());
```

## Your own executors

Wrap once, and stop thinking about it:

```java
@Bean
ExecutorService reportPool() {
    return TenantExecutors.wrap(Executors.newFixedThreadPool(8));
}
```

The wrapper covers `execute`, `submit`, `invokeAll` and `invokeAny`, so every entry point
carries the tenant rather than only the one you remembered.

```java
// All of these are now tenant-aware.
reportPool.submit(() -> reports.build());
reportPool.invokeAll(List.of(() -> reports.build(), () -> invoices.total()));
```

## Scheduled jobs

A `@Scheduled` method runs on a scheduler thread no filter ever touched. There is no
request, so there is no tenant to propagate — you choose which tenants to run for:

```java
@Component
public class NightlyRollup {

    private final TenantTasks tenants;

    @Scheduled(cron = "0 0 2 * * *")
    void everyTenant() {
        tenants.forEachTenant(tenantId -> rollups.rebuild());
    }

    @Scheduled(fixedDelay = 60_000)
    void oneKnownTenant() {
        tenants.runAs("internal", () -> housekeeping.run());
    }
}
```

`forEachTenant` attempts every tenant, skips suspended ones, and throws at the end with the
failures named — so one bad tenant does not silently cost you the rest of the night's work.

**The scheduler thread is left as it was found.** Schedulers pool threads, and a job that
leaves a tenant bound hands it to the next job on that thread.

## Parallel streams

`parallelStream()` uses the common ForkJoinPool, same as `supplyAsync`. There is no
decorator for it, and the fix is not to reach for one:

```java
// Wrong — the mapping runs on ForkJoinPool threads with no tenant.
orders.parallelStream().map(pricing::quote).toList();

// Right — do the tenant-scoped work on a wrapped executor.
List<Callable<Quote>> work = orders.stream()
        .map(o -> (Callable<Quote>) () -> pricing.quote(o))
        .toList();
List<Future<Quote>> quotes = reportPool.invokeAll(work);   // reportPool is wrapped
```

## Proving any of it

Propagation failures are invisible, so they need an explicit test:

```java
@Test
@WithTenant("acme")
void theTenantSurvivesTheThreadHop() throws Exception {
    CompletableFuture<String> seen = new CompletableFuture<>();

    probe.recordTenantAsync(seen);      // an @Async method that reports its own tenant

    assertThat(seen.get(5, SECONDS))
            .as("the tenant was lost crossing to the pool thread")
            .isEqualTo("acme");
}
```

Make it fail first. Have the probe run on the calling thread and check the assertion still
passes — if it does, the test never crossed a boundary and proves nothing. That exact
mistake is why this library tests propagation by breaking it.

See [context propagation](context-propagation.md) for the overview,
[Kafka](kafka.md) for message boundaries, and
[the tenant registry](tenant-registry.md) for where `forEachTenant` gets its list.
