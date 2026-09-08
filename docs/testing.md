# Testing

## The assertion

```java
@Test
@WithTenant("acme")
void oneTenantCannotSeeAnother() {
    assertThat(orders.findAll()).isNotEmpty();
    assertTenantCannotSee("globex");
}
```

`assertTenantCannotSee` is deliberately two-sided. Proving "tenant A sees no rows belonging
to B" is worthless alone — an empty table passes it. So it first checks, on a privileged
connection that bypasses the policy, that B's rows genuinely exist, and fails with a message
telling you to seed them if they do not. It also refuses to run with no tenant bound, and
refuses when the acting tenant *is* the tenant you asked about.

## The fixture

```java
static final TenantPostgres POSTGRES = TenantPostgres.start()
        .withTenantTable("orders", "item varchar(255) not null")
        .withRegistry("acme", "globex");

@BeforeEach
void bind() {
    POSTGRES.seedRow("orders", "acme",   Map.of("item", "laptop"));
    POSTGRES.seedRow("orders", "globex", Map.of("item", "monitor"));
    IsolationAssertions.bind(POSTGRES.applicationDataSource(), POSTGRES.privilegedDataSource());
    IsolationAssertions.bindTable("orders", "tenant_id");
}
```

`withTenantTable` creates the table with the index, `FORCE ROW LEVEL SECURITY` and a
correctly guarded policy. `withUnprotectedTable` creates one without a policy, for testing
the discriminator strategy on its own.

### Two DataSources, and they are not interchangeable

- `applicationDataSource()` — a least-privileged role, neither superuser nor owner. This is
  what the code under test uses, and it is subject to the policy.
- `privilegedDataSource()` — bypasses the policy. For seeding and for proving another
  tenant's rows exist. Never the subject of an assertion.

Standing up a Postgres container is one line. Standing up one that can *prove* isolation is
not, and the trap is the connection you test through: Testcontainers hands you a superuser,
superusers bypass RLS outright, and a suite written against that connection passes whether
or not the policy works — including after someone deletes it.

The pool defaults to one connection, so a tenant left behind on a recycled connection is
observed deterministically rather than occasionally.

## A complete test class

`TenantPostgres` is a standalone fixture — it does not need a Spring context, which keeps
isolation tests fast and keeps them testing the database rather than your wiring:

```java
class ReportIsolationTest {

    private static TenantPostgres postgres;
    private static DataSource application;

    @BeforeAll
    static void startDatabase() {
        postgres = TenantPostgres.start()
                .withTenantTable("reports", "title varchar(255) not null")
                .withRegistry("acme", "globex");
        // One connection, so a tenant left behind on a recycled connection is
        // observed every time rather than occasionally.
        application = postgres.applicationDataSource(1);
    }

    @BeforeEach
    void seed() {
        postgres.execute("truncate table reports restart identity");
        postgres.seedRow("reports", "acme",   Map.of("title", "acme q3"));
        postgres.seedRow("reports", "globex", Map.of("title", "globex q3"));

        IsolationAssertions.bind(application, postgres.privilegedDataSource());
        IsolationAssertions.bindTable("reports", "tenant_id");
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    @WithTenant("acme")
    void acmeSeesOnlyItsOwnReports() {
        assertTenantCannotSee("globex");
    }
}
```

`withTenantTable` creates the table, the index, `FORCE ROW LEVEL SECURITY` and a correctly
guarded policy — so the fixture cannot pass for the wrong reason, which is the usual way an
isolation suite goes green while proving nothing.

## Testing over HTTP

The test above proves the database enforces isolation. This one proves your endpoints do
not undo it — which is a different claim, and the one your users actually depend on:

```java
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class OrderApiTest {

    @Autowired
    private TestRestTemplate http;

    @Test
    void oneTenantCannotFetchAnothersOrderById() {
        long globexOrder = place("globex", "monitor");

        // acme knows the id and asks for it directly.
        ResponseEntity<String> response = get("/orders/" + globexOrder, "acme");

        assertThat(response.getStatusCode())
                .as("acme fetched globex's order by id")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aRequestWithNoTenantIsRejectedRatherThanServedEmpty() {
        ResponseEntity<String> response = http.exchange(
                "/orders", HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private ResponseEntity<String> get(String path, String tenant) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Tenant-ID", tenant);
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }
}
```

The second test is the one people leave out. Without it, turning strict mode off looks like
a passing suite.

## Testing that the tenant survives a thread hop

Propagation failures are silent — the work runs with no tenant and returns nothing — so
they need an explicit test:

```java
@Test
@WithTenant("acme")
void theTenantSurvivesAnAsyncCall() throws Exception {
    CompletableFuture<String> seen = new CompletableFuture<>();

    asyncProbe.record(seen);          // an @Async method that reports its tenant

    assertThat(seen.get(5, SECONDS))
            .as("the tenant was lost crossing to the pool thread")
            .isEqualTo("acme");
}
```

Make it fail once before you trust it: have the probe run on the calling thread, and check
the assertion still passes. If it does, the test never crossed a thread boundary and proves
nothing.

## Break it and watch it fail

Green tests are not evidence. After a test passes, break the implementation and confirm the
test goes red. Every isolation claim in this library was checked that way, and it caught
something real every time — including a `BUILD SUCCESS` with zero tests executed, an
`@Async` test that would have passed if the work never left the calling thread, and an
`assertTenantCannotSee` that passed with the library switched off entirely.

The cheapest version of this for your own code:

```
mvn test -Dspring.autoconfigure.exclude=io.tenantlayer.autoconfigure.TenantLayerAutoConfiguration
```

If your isolation tests still pass with TenantLayer turned off, they are not testing
isolation.
