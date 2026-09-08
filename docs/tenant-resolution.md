# Tenant resolution

Every request has to be attributed to exactly one tenant before it touches the database.
Resolution is that step: it reads the tenant from the request, and everything downstream —
the connection, the cache key, the log line, the background job — follows from it.

```properties
tenantlayer.resolvers=JWT,HEADER,SUBDOMAIN,PATH
```

Order is precedence, first match wins. **List the source you trust most first** — a
spoofable header should never outrank a signed claim.

| Source | Reads | Config |
|---|---|---|
| `JWT` | a claim from a validated token | `tenantlayer.jwt-claim` (default `tenant_id`) |
| `HEADER` | a request header | `tenantlayer.header` (default `X-Tenant-ID`) |
| `SUBDOMAIN` | the first label of the host | `tenantlayer.base-domain` |
| `PATH` | a path segment, `/t/{tenant}/...` | `tenantlayer.path-prefix` (default `/t`) |

## Which one should you use?

| If your callers are | Use | Because |
|---|---|---|
| Browsers with a signed-in user | `JWT` | The tenant is already in the token your identity provider signed. Nothing to spoof. |
| Internal services you control | `HEADER` | Simple, and the network boundary is the trust boundary. Pair with membership verification if it is reachable from outside. |
| Customers on their own subdomain | `SUBDOMAIN` | `acme.app.com` is the tenant, and it is visible in every link and bookmark. |
| A single host serving many tenants | `PATH` | `/t/acme/orders` works without DNS or certificates per tenant. |

You are not choosing one forever. A chain lets a browser use its token while a background
integration uses a header.

---

## JWT — the tenant comes from a signed token

The claim is read from a token Spring Security has already validated, so a caller cannot
change it without invalidating the signature.

```properties
tenantlayer.resolvers=JWT
tenantlayer.jwt-claim=tenant_id
spring.security.oauth2.resourceserver.jwt.issuer-uri=https://your-idp.example.com/
```

A token carrying:

```json
{ "sub": "user-42", "tenant_id": "acme", "iss": "https://your-idp.example.com/" }
```

resolves to `acme`:

```bash
curl https://app.com/orders -H "Authorization: Bearer $TOKEN"
```

If your provider nests the tenant rather than exposing a flat claim, there may already be a
preset for it — see [provider presets](#provider-presets) below. Failing that,
[write a resolver](#writing-your-own).

> Requires `spring-boot-starter-oauth2-resource-server` on the classpath. It is an optional
> dependency, so nothing is pulled in unless you use it.

## HEADER — the tenant is sent explicitly

```properties
tenantlayer.resolvers=HEADER
tenantlayer.header=X-Tenant-ID
```

```bash
curl https://app.com/orders -H "X-Tenant-ID: acme"
```

**A header is a claim, not a proof.** Anyone who can reach the endpoint can send any value.
That is fine between services on a private network, and dangerous the moment the endpoint is
public. If callers are authenticated, turn on
[membership verification](#membership-verification) so a claimed tenant is checked against
who the caller actually is.

The same header name is used on outbound calls, so a service that calls another service
propagates the tenant without any code — see [context propagation](context-propagation.md).

## SUBDOMAIN — the tenant is the host

```properties
tenantlayer.resolvers=SUBDOMAIN
tenantlayer.base-domain=app.com
```

| Request host | Resolves to | Why |
|---|---|---|
| `acme.app.com` | `acme` | The label in front of the base domain |
| `app.com` | *nothing* | No subdomain to read |
| `www.app.com` | *nothing* | `www` is not a tenant |
| `a.b.app.com` | *nothing* | Ambiguous, so refused rather than guessed |

Leaving `base-domain` unset makes the resolver take the first label of whatever host
arrives, which is convenient locally and too loose for production — set it.

**Refusing beats guessing.** Inventing a tenant from `www` would produce a plausible empty
page; resolving nothing lets strict mode return a clear 400.

## PATH — the tenant is in the URL

```properties
tenantlayer.resolvers=PATH
tenantlayer.path-prefix=/t
```

```bash
curl https://app.com/t/acme/orders     # resolves to acme
```

Your controllers do not need to know about the prefix — it is read and the tenant bound
before your mapping runs.

---

## Provider presets

The `JWT` resolver reads one configurable claim, which is enough when you control the
token shape. Identity providers do not agree on where the tenant id lives, so two thin
presets exist for the common cases:

| Provider | Class | Claim shape |
|---|---|---|
| Auth0 | `AuthPresets.forAuth0()` | flat `org_id` string |
| Keycloak | `new KeycloakOrganizationClaimResolver()` | nested `organization` object or array |

```java
@Bean
TenantResolver<HttpServletRequest> tenantResolver() {
    return AuthPresets.forAuth0();
}
```

Auth0's Organizations feature puts the org id in a flat `org_id` claim, so it is just the
existing `JwtClaimTenantResolver` pointed at that claim name.

Keycloak's built-in Organizations feature (26+) is not a flat claim. Depending on how the
Organization Membership Mapper is configured, the token carries either a plain array of
organization names or a map keyed by name with an `id` inside:

```json
"organization": ["acme-corp"]
"organization": { "acme-corp": { "id": "f8d3c4e1-..." } }
```

More than one organization in the map form is refused rather than guessed at: a JSON object
parses into a `HashMap`, whose iteration order is not token order, so "pick the first one"
would silently attach a request to an arbitrary organization. `Optional.empty()` is returned
instead, and strict mode turns that into a 400.

The id vs. name choice is tied to a Keycloak admin setting ("Add organization id" on the
mapper). Flipping that setting after tenants already have data keyed by the old value will
make that data invisible under the new one — pin the setting, do not toggle it later.

Clerk and WorkOS also carry an org id (`o.id` and `org_id` respectively) but are not covered
by a preset yet; `JwtClaimTenantResolver` handles WorkOS's flat claim directly, and Clerk's
nested `o` claim would need the same kind of resolver as Keycloak's.

---

## Chains: more than one source

Precedence is the order you list, and the first resolver with an opinion wins. The rest are
never consulted.

```properties
tenantlayer.resolvers=JWT,HEADER
```

| Request | Resolves to | Why |
|---|---|---|
| Token says `acme`, no header | `acme` | JWT matched |
| Token says `acme`, header says `globex` | `acme` | **JWT is listed first and wins** |
| No token, header says `globex` | `globex` | JWT had no opinion, so the chain fell through |
| Neither | *nothing* | Strict mode rejects the request |

Row two is the point of ordering. A caller who holds a token for `acme` cannot reach
`globex`'s data by adding a header, because the trusted source is consulted first.

## Strict mode

```properties
tenantlayer.strict=true
```

On by default. A request with no resolvable tenant is rejected:

```
HTTP/1.1 400 Bad Request

{"error":"no tenant could be resolved from this request"}
```

The alternative — carrying on with no tenant — yields an empty result set, which reads as
"no data" and sends the caller hunting for a bug in their query rather than in their request.

Paths that legitimately have no tenant are listed rather than guessed:

```properties
tenantlayer.unscoped-paths=/actuator,/error,/login
```

Anything under a listed prefix skips resolution entirely. Keep this list short and specific:
every entry is a route where tenant isolation does not apply.

## Membership verification

Resolution answers *which tenant does this request claim to be*. Verification answers
*is this caller allowed to be that tenant*. They are different jobs, and a public endpoint
needs both.

```properties
tenantlayer.membership.enabled=true
tenantlayer.membership.claim=tenants
```

With a token carrying the tenants a user actually belongs to:

```json
{ "sub": "user-42", "tenants": ["acme", "beta-corp"] }
```

| Request | Result |
|---|---|
| `X-Tenant-ID: acme` | Allowed — `acme` is in the token |
| `X-Tenant-ID: globex` | **403** — the caller claimed a tenant they do not belong to |

Without this, a header-resolved tenant is whatever the caller typed. With it, the claim is
checked against something they cannot forge.

For anything more involved than a claim — a database lookup, a permissions service —
implement `TenantMembershipVerifier`:

```java
@Bean
TenantMembershipVerifier tenantMembershipVerifier(MembershipRepository memberships) {
    return tenantId -> {
        Authentication caller = SecurityContextHolder.getContext().getAuthentication();
        return caller != null && memberships.exists(caller.getName(), tenantId);
    };
}
```

It takes only the tenant — the caller comes from the `SecurityContext`, which Spring
Security has already populated by the time this runs.

Returning `false` produces a 403. See [securing resolution](securing-resolution.md).

## Writing your own

`TenantResolver<S>` is the public extension point. Define the bean and the autoconfigured
chain backs off.

```java
@Bean
TenantResolver<HttpServletRequest> tenantResolver(ApiKeys apiKeys) {
    return request -> Optional.ofNullable(request.getHeader("X-Api-Key"))
            .flatMap(apiKeys::tenantFor);
}
```

Return `Optional.empty()` when your resolver has no opinion, so a chain can fall through
rather than treating "I don't know" as "no tenant".

A common case is a provider that nests the tenant instead of exposing a flat claim:

```java
@Bean
TenantResolver<HttpServletRequest> tenantResolver() {
    return request -> {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            return Optional.empty();
        }
        // { "org": { "id": "acme", "name": "Acme Corp" } }
        Object org = jwt.getClaim("org");
        return org instanceof Map<?, ?> map && map.get("id") instanceof String id
                ? Optional.of(id)
                : Optional.empty();
    };
}
```

## Testing resolution

`@WithTenant` binds a tenant for the duration of a test, so you can exercise
tenant-scoped code without going through HTTP:

```java
@Test
@WithTenant("acme")
void onlyReturnsAcmeOrders() {
    assertThat(orders.findAll()).allMatch(o -> o.getTenantId().equals("acme"));
}
```

And over HTTP, assert that resolution actually rejects what it should:

```java
@Test
void requestWithoutATenantIsRejected() {
    ResponseEntity<String> response = http.getForEntity("/orders", String.class);

    assertThat(response.getStatusCode())
            .as("strict mode must reject rather than quietly return nothing")
            .isEqualTo(HttpStatus.BAD_REQUEST);
}
```

The second test matters more than it looks. It is the one that fails if someone turns
strict mode off, and an empty result set is exactly what a broken tenancy setup looks like.

## Common mistakes

**Listing `HEADER` before `JWT`.** The chain is precedence. Putting the spoofable source
first means a caller can override their own token.

**Leaving `base-domain` unset in production.** The subdomain resolver then trusts the first
label of any host it is given.

**Turning off strict mode to fix a failing health check.** Add the path to
`unscoped-paths` instead — one route stops being tenant-scoped, rather than all of them.

**Assuming resolution is authorisation.** It is not. Resolution reads a claim; membership
verification checks it. See [securing resolution](securing-resolution.md).
