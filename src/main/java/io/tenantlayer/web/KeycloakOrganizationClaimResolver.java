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
 * Feature 111 preset — Keycloak's built-in Organizations feature (Keycloak 26+).
 *
 * <p>Unlike a provider that puts the tenant id in a flat string claim, Keycloak nests it.
 * Depending on how the Organization Membership Mapper is configured, the {@code organization}
 * claim takes one of two shapes:
 *
 * <pre>{@code
 * // default: string array of organization names, no id
 * "organization": ["acme-corp"]
 *
 * // "Add organization id" enabled on the mapper: map of name -> { id, ... }
 * "organization": { "acme-corp": { "id": "f8d3c4e1-..." } }
 * }</pre>
 *
 * <p>This resolver prefers the id from the rich map form when present, and falls back to the
 * organization name when only the array form is available. If a user belongs to more than one
 * organization, the first entry is used — Keycloak has no concept of a single "active"
 * organization at the token level, so callers who need to disambiguate should not rely on this
 * resolver alone.
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
            Object firstKey = orgMap.keySet().iterator().next();
            Object details = orgMap.get(firstKey);
            if (details instanceof Map<?, ?> orgDetails
                    && orgDetails.get("id") instanceof String id
                    && !id.isBlank()) {
                return Optional.of(id);
            }
            return Optional.of(String.valueOf(firstKey));
        }

        if (claim instanceof List<?> orgList && !orgList.isEmpty()) {
            Object first = orgList.get(0);
            return first == null ? Optional.empty() : Optional.of(String.valueOf(first));
        }

        return Optional.empty();
    }

    public String claimName() {
        return claimName;
    }
}