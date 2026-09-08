# Getting started

## 1. Add the dependency

```xml
<dependency>
  <groupId>io.tenantlayer</groupId>
  <artifactId>tenantlayer-spring-boot-starter</artifactId>
  <version>0.3.0</version>
</dependency>
```

Java 17+, Spring Boot 3.3+, Postgres. Hibernate 6 and 7 are both supported.

## 2. Configure resolution and your datasource

```properties
tenantlayer.resolvers=HEADER
tenantlayer.header=X-Tenant-ID
tenantlayer.strict=true

spring.datasource.url=jdbc:postgresql://localhost:5432/app
spring.datasource.username=orders_app
spring.datasource.password=${DB_PASSWORD}
```

Nothing else changes about your datasource — TenantLayer wraps whichever one your
application already defines, so your pool settings, your URL and your credentials stay
exactly as they are.

`strict=true` is the default and should stay that way. A request with no resolvable tenant
is rejected with 400 rather than served with no tenant bound — which would return an empty
result set that reads as "no data" and sends the caller hunting for a bug in their query.

> **Read [Securing resolution](securing-resolution.md) before exposing this to the
> internet.** A header is whatever the caller typed. It is safe behind a gateway that
> overwrites it, and unsafe otherwise.

## 3. Give your tables a tenant column

```sql
create table orders (
    id           bigserial primary key,
    -- Filled in by the database from the connection's tenant. Your code never sets it.
    tenant_id    varchar(64)  not null default current_setting('tenantlayer.tenant', true),
    customer     varchar(255) not null,
    amount_cents bigint       not null
);

create index idx_orders_tenant on orders (tenant_id);
```

The entity has no tenancy logic either — the column is read back after insert, never
written:

```java
@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String customer;

    @Column(name = "amount_cents")
    private long amountCents;

    @Generated(event = EventType.INSERT)
    @Column(name = "tenant_id", insertable = false, updatable = false)
    private String tenantId;
}
```

## 4. Add the policy

Generate it rather than writing it by hand:

```java
@Autowired TenantScopedEntityScanner scanner;
@Autowired RlsPolicyGenerator generator;

System.out.println(generator.generate(scanner.scan()));
```

Review the output, commit it as a migration, apply it with Flyway or Liquibase. It is plain
SQL — the policies keep working if you remove TenantLayer.

## 5. Connect as a role that is not the owner

This is the step people skip, and skipping it silently disables everything above.

```sql
create role orders_app login password '...';
grant usage on schema public to orders_app;
grant select, insert, update, delete on orders to orders_app;
```

A Postgres **superuser bypasses row-level security entirely**, and so does the table owner
unless the table has `FORCE ROW LEVEL SECURITY` (the generator emits it). Connect as a
least-privileged role, or your policies are decorative.

## 6. Write nothing else

```java
@RestController
@RequestMapping("/orders")
class OrderController {

    private final OrderRepository orders;

    @GetMapping
    List<Order> list() {
        return orders.findAll();   // no tenant parameter, no filter, no predicate
    }
}
```

`findAll()` emits `select ... from orders` with no `where tenant_id = ?`. The rows come
back scoped because Postgres applies the policy to the connection.

Two requests, two answers, one endpoint:

```bash
curl localhost:8080/orders -H "X-Tenant-ID: acme"
# [{"id":1,"customer":"Wile E. Coyote","amountCents":4999,"tenantId":"acme"}]

curl localhost:8080/orders -H "X-Tenant-ID: globex"
# [{"id":2,"customer":"Hank Scorpio","amountCents":999999,"tenantId":"globex"}]

curl localhost:8080/orders
# 400 — strict mode, no tenant could be resolved
```

## 7. Prove it

```java
@Test
@WithTenant("acme")
void oneTenantCannotSeeAnother() {
    assertThat(orders.findAll()).isNotEmpty();   // acme can see its own
    assertTenantCannotSee("globex");             // and nothing of globex's
}
```

Both halves matter. "Cannot see the other tenant" passes trivially against an empty table;
`assertTenantCannotSee` refuses to run unless the other tenant genuinely has rows. See
[Testing](testing.md).


## Where to go next

You now have isolation on one datasource, for requests that carry a header. The next
questions people hit, in the order they hit them:

| Next | Guide |
|---|---|
| "A header is spoofable — how do I resolve from a token?" | [Tenant resolution](tenant-resolution.md) |
| "How do I stop a caller claiming a tenant they don't belong to?" | [Securing resolution](securing-resolution.md) |
| "My `@Async` method returns nothing" | [Context propagation](context-propagation.md) |
| "How do I run a nightly job for every tenant?" | [The tenant registry](tenant-registry.md) |
| "I use `@Cacheable`" | [Caching](caching.md) — read this one, it is a hole RLS cannot cover |
| "How do I migrate every tenant's schema?" | [Migrations](migrations.md) |
| "I want a schema or a database per tenant instead" | [Isolation strategies](isolation-strategies.md) |
| "It returns nothing and I don't know why" | [Troubleshooting](troubleshooting.md) |

If you are adding this to a system that already has customers rather than a new one, read
[Adopting in an existing app](adopting-in-an-existing-app.md) instead — it goes on
gradually, table by table, rather than as a flag day.
