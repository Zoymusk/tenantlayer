# Tenant resolution

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

## Strict mode

```properties
tenantlayer.strict=true
```

On by default. A request with no resolvable tenant is rejected with 400. The alternative —
carry on with no tenant — yields an empty result set, which reads as "no data" and sends
the caller hunting for a bug in their query rather than in their request.

Paths that legitimately have no tenant are listed rather than guessed:

```properties
tenantlayer.unscoped-paths=/actuator,/error,/login
```

## The subdomain resolver refuses ambiguity

`www.app.com` does not become a tenant named `www`, `app.com` with no subdomain does not
resolve, and a multi-label prefix is refused rather than guessed at. Silently inventing a
tenant is worse than resolving none, because strict mode turns "none" into a clear 400
while an invented one produces a plausible empty page.

## Writing your own

`TenantResolver<S>` is the product's public extension point:

```java
@Bean
TenantResolver<HttpServletRequest> tenantResolver() {
    return request -> Optional.ofNullable(request.getHeader("X-Api-Key"))
            .flatMap(apiKeys::tenantFor);
}
```

Return `Optional.empty()` when your resolver has no opinion, so a chain can fall through.
Define the bean and the autoconfigured chain backs off.
