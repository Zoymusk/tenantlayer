# Context storage

## Where the tenant is kept

`TenantContext` delegates to a `TenantContextStorage`. The shipped implementation is a
plain `ThreadLocal`.

```java
public interface TenantContextStorage {
    TenantScope get();
    void set(TenantScope scope);
    void clear();
}
```

## Why it is an interface with one implementation

Every propagation adapter in the library — the servlet filter, the task decorator, the
executor wrapper, the Kafka interceptors, the scheduler helper — reads and writes the
context. If the storage mechanism changes later and those call sites each reach for their
own `ThreadLocal`, every one of them is a separate migration and a separate chance to get
it wrong. They all go through `TenantContext`, and `TenantContext` goes through this.

## Why not `ScopedValue` yet

`ScopedValue` is the better answer: immutable for the duration of a binding, inherited by
structured-concurrency forks automatically, and impossible to leave behind on a pooled
thread because there is no setter to forget to unset.

It is a **preview API on JDK 21 through 24** and final only in JDK 25. Shipping an
implementation now would force `--enable-preview` on every consumer, and TenantLayer's
baseline is Java 17 so that Spring Boot 3.x applications can adopt it. The seam is defined;
the implementation lands when the baseline reaches a JDK where the API is final.

## Why not `InheritableThreadLocal`

It sounds like exactly what a propagation library wants, and it is a trap. It copies the
value at thread *creation*, which for a pooled executor is whenever the pool happened to
grow. A worker created while serving acme keeps acme as its inherited default forever, and
every later task that fails to set a tenant runs as acme instead of failing closed.

Propagation is explicit instead: decorators capture at submit time and restore afterwards.

## Substituting your own

Three methods, and a contract that matters more than the code:

```java
public class DiagnosticTenantStorage implements TenantContextStorage {

    private static final Logger log = LoggerFactory.getLogger(DiagnosticTenantStorage.class);
    private final ThreadLocal<TenantScope> current = new ThreadLocal<>();

    @Override
    public TenantScope get() {
        return current.get();          // null means "no tenant" — never a default
    }

    @Override
    public void set(TenantScope scope) {
        TenantScope previous = current.get();
        if (previous != null && scope != null && !previous.subject().equals(scope.subject())) {
            // A thread changing tenant without unwinding first is worth knowing about.
            log.warn("tenant changed from {} to {} on {} without a clear()",
                    previous.subject(), scope.subject(), Thread.currentThread().getName());
        }
        current.set(scope);
    }

    @Override
    public void clear() {
        current.remove();              // remove, not set(null) — a pooled thread keeps the entry
    }
}
```

```java
@PostConstruct
void useDiagnosticStorage() {
    TenantContext.useStorage(new DiagnosticTenantStorage());
}
```

Call it once during start-up, before any tenant is bound.

**The contract:**

- **Safe for concurrent use**, and one thread must never observe another's tenant. This is
  the whole reason a naive `static TenantScope` fails immediately under load.
- **`get()` returning `null` means no tenant**, and callers on the enforcement path treat
  that as fail-closed. Never substitute a default, and never return the last tenant this
  thread saw.
- **`clear()` must actually release the entry.** `ThreadLocal.remove()` rather than
  `set(null)`, or a pooled thread retains a map entry for the life of the pool.

### Testing a replacement

Isolation depends on this class, so test it as the concurrency primitive it is:

```java
@Test
void oneThreadNeverSeesAnothersTenant() throws Exception {
    TenantContextStorage storage = new DiagnosticTenantStorage();
    storage.set(TenantScope.of("acme"));

    String seenOnAnotherThread = CompletableFuture
            .supplyAsync(() -> storage.get() == null ? "none" : storage.get().subject())
            .get(5, SECONDS);

    assertThat(seenOnAnotherThread)
            .as("a second thread inherited the first thread's tenant")
            .isEqualTo("none");

    assertThat(storage.get().subject()).isEqualTo("acme");
    storage.clear();
    assertThat(storage.get()).isNull();
}
```

That test is the one that fails if someone reaches for `InheritableThreadLocal` — which is
exactly the mistake the section above exists to prevent.
