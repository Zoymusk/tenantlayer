# Outbound HTTP

When one of your services calls another, the tenant has to travel with the call. TenantLayer
attaches it to every outbound request automatically, for all four clients Spring
applications actually use.

| Client | How | Dependency |
|---|---|---|
| `RestClient`, `RestTemplate` | `ClientHttpRequestInterceptor` | in the starter |
| `WebClient` | `ExchangeFilterFunction` | `spring-webflux` |
| Feign | `RequestInterceptor` | `feign-core` |

The header is whatever `tenantlayer.header` says, defaulting to `X-Tenant-ID` — so the
calling service sends exactly what the receiving service resolves, by configuration rather
than by convention.

## RestClient and RestTemplate

Build from the injected builder and the interceptor is already there:

```java
@Configuration
class Clients {

    @Bean
    RestClient billing(RestClient.Builder builder) {
        return builder.baseUrl("https://billing.internal").build();
    }
}
```

```java
@Service
class SubscriptionService {

    private final RestClient billing;

    Invoice latest() {
        // X-Tenant-ID: acme  — attached from the bound tenant, not from this code.
        return billing.get().uri("/invoices/latest").retrieve().body(Invoice.class);
    }
}
```

> **Use the injected builder.** `new RestTemplate()` or `RestClient.create()` bypasses
> Boot's customisers, which means no interceptor and no header. The call still succeeds — it
> just arrives with no tenant, and the receiving service returns nothing.

## WebClient

```java
@Bean
WebClient billing(WebClient.Builder builder) {
    return builder.baseUrl("https://billing.internal").build();
}
```

Same rule: build from the injected builder, not `WebClient.create()`.

Note that the tenant is captured when the request is *assembled*, on the calling thread.
A reactive chain assembled inside a request and subscribed later still carries the right
tenant; one assembled on a scheduler thread with nothing bound carries none.

## Feign

```java
@FeignClient(name = "billing", url = "https://billing.internal")
interface BillingClient {

    @GetMapping("/invoices/latest")
    Invoice latest();
}
```

Nothing to configure — the interceptor is a bean and Feign picks it up.

## A call made with no tenant sends no header

Not an empty one. An empty value is a claim about the tenant; its absence is not, and the
receiving service can tell the difference:

```
tenant bound     → X-Tenant-ID: acme
no tenant bound  → (no header at all)
```

With strict mode on at the other end, the second is a clean 400 rather than a request that
resolves to the empty tenant and quietly returns nothing.

## The receiving side is not authenticated by this

Outbound propagation is **context propagation, not authentication**. The service receiving
that header must treat it as client-supplied, because from its point of view it is —
whatever your intent, that is a header on an inbound request.

That is fine when the network is the trust boundary. It is not fine the moment the service
is reachable from outside, and "reachable from outside" is rarely a decision anyone
announces. A service exposed beyond your perimeter should resolve from a token and verify
membership; see [securing resolution](securing-resolution.md).

## Calling a service that does not use TenantLayer

Send the header yourself:

```java
billing.get()
        .uri("/invoices/latest")
        .header("X-Account-Id", TenantContext.require().subject())
        .retrieve()
        .body(Invoice.class);
```

`require()` throws `NoTenantException` when nothing is bound, which is the correct failure —
better than sending a request with a missing account and getting an ambiguous answer.

## Testing it

Assert the header, not just the response:

```java
@Test
@WithTenant("acme")
void theTenantTravelsWithTheCall() {
    mockServer.expect(requestTo("/invoices/latest"))
            .andExpect(header("X-Tenant-ID", "acme"))
            .andRespond(withSuccess());

    subscriptions.latest();

    mockServer.verify();
}

@Test
void noTenantMeansNoHeader() {
    TenantContext.clear();

    mockServer.expect(requestTo("/invoices/latest"))
            .andExpect(headerDoesNotExist("X-Tenant-ID"))
            .andRespond(withSuccess());

    subscriptions.latest();

    mockServer.verify();
}
```

The second test is the one people skip, and it is the one that catches a client built with
`new RestTemplate()` — which would send no header for a completely different reason and
look identical from the outside.

See [context propagation](context-propagation.md) for the other boundaries.
