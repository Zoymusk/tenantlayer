package io.tenantlayer.web;
 
import io.tenantlayer.core.TenantResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
 
/**
 * Preset for Keycloak's built-in Organizations feature (Keycloak 26+).
 *
 * <p>Unlike a provider that puts the tenant id in a flat string claim, Keycloak nests it.
 * Depending on how the Organization Membership Mapper is configured, the {@code organization}
 * claim takes one of two shapes:
 *
 * <pre>{@code
 * // default: string array of organization names, no id. Array order matches token order.
 * "organization": ["acme-corp"]
 *
 * // "Add organization id" enabled on the mapper: map of name -> { id, ... }
 * "organization": { "acme-corp": { "id": "f8d3c4e1-..." } }
 * }</pre>
 *
 * <p><strong>Ambiguity is refused, not guessed.</strong> In the array form, a single entry
 * is used directly. In the map form, this resolver only returns a value when exactly one
 * organization is present. A JSON object with more than one entry parses into a
 * {@link java.util.HashMap}, whose iteration order is hash order, not token order — picking
 * "the first one" would silently attach a request to an arbitrary organization depending on
 * JVM hashing, not on anything in the token. That is worse than resolving none: strict mode
 * turns "none" into a clear 400, while an arbitrarily chosen organization produces a
 * plausible, wrong result. Callers who need to handle users with multiple organizations
 * should resolve tenancy some other way (for example, an explicit header naming the active
 * organization) rather than relying on this resolver alone.
 *
 * <p><strong>The id-map fallback is tied to a Keycloak admin setting.</strong> With
 * "Add organization id" off, this resolver returns the organization name (for example
 * {@code acme-corp}). Turn that setting on and it starts returning the organization's id
 * (for example {@code f8d3c4e1-...}) instead. Any row-level security policy or cache keyed
 * by the old value goes invisible under the new one. If you rely on this resolver, decide on
 * a value (name or id) and pin the mapper setting to match — do not flip it after tenants
 * have data keyed by the old value.
 *
 * @see <a href="https://www.keycloak.org/2026/04/org-groups">Keycloak: Organization Groups</a>
 */
public class KeycloakOrganizationClaimResolver implements TenantResolver<HttpServletRequest> {
 
    public static final String DEFAULT_CLAIM = "organization";
 
    private final String claimName;
 
    public KeycloakOrganizationClaimResolver() {
        this(DEFAULT_CLAIM);
    }
 
    public KeycloakOrganizationClaimResolver(String claimName) {
        this.claimName = claimName == null || claimName.isBlank() ? DEFAULT_CLAIM : claimName;
    }
 
    @Override
    public Optional<String> resolve(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        if (!(authentication.getPrincipal() instanceof Jwt jwt)) {
            return Optional.empty();
        }
 
        Object claim = jwt.getClaim(claimName);
 
        if (claim instanceof Map<?, ?> orgMap && !orgMap.isEmpty()) {
            if (orgMap.size() > 1) {
                // More than one organization in the rich map form. HashMap iteration order
                // is not token order, so there is no safe way to pick "the first" one here.
                // Refuse rather than guess; strict mode will reject the request with 400.
                return Optional.empty();
            }
            Object onlyKey = orgMap.keySet().iterator().next();
            Object details = orgMap.get(onlyKey);
            if (details instanceof Map<?, ?> orgDetails
                    && orgDetails.get("id") instanceof String id
                    && !id.isBlank()) {
                return Optional.of(id);
            }
            return Optional.of(String.valueOf(onlyKey));
        }
 
        if (claim instanceof List<?> orgList && !orgList.isEmpty()) {
            // A JSON array parses into a List, which preserves token order, so the first
            // element here really is the first organization in the token.
            Object first = orgList.get(0);
            return first == null ? Optional.empty() : Optional.of(String.valueOf(first));
        }
 
        return Optional.empty();
    }
 
    public String claimName() {
        return claimName;
    }
}
