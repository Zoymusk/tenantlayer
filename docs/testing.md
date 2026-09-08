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

Everything above, in one file you can copy:

```java
@SpringBootTest
@Testcontainers
class OrderIsolationTest {

    static final TenantPostgres POSTGRES = TenantPostgres.start()
            .withTenantTable("orders", "item varchar(255) not null")
            .withRegistry("acme", "globex");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getApplicationUsername);
        registry.add("spring.datasource.password", POSTGRES::getApplicationPassword);
        // Boot auto-configures Flyway from the classpath alone; this test is not about migrations.
        registry.add("spring.flyway.enabled", () -> "false");
    }

    @Autowired
    private OrderRepository orders;

    @BeforeEach
    void seed() {
        POSTGRES.seedRow("orders", "acme",   Map.of("item", "laptop"));
        POSTGRES.seedRow("orders", "globex", Map.of("item", "monitor"));
        IsolationAssertions.bind(POSTGRES.applicationDataSource(), POSTGRES.privilegedDataSource());
        IsolationAssertions.bindTable("orders", "tenant_id");
    }

    @Test
    @WithTenant("acme")
    void acmeSeesOnlyItsOwnOrders() {
        assertThat(orders.findAll()).isNotEmpty();
        assertTenantCannotSee("globex");
    }

    @Test
    @WithTenant("acme")
    void writesAreStampedWithTheActingTenant() {
        Order saved = orders.save(new Order("keyboard"));

        // The application never set this. The connection's tenant did.
        assertThat(saved.getTenantId()).isEqualTo("acme");
    }
}
```

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
