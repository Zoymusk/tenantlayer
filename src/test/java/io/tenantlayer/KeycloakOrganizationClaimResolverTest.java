package io.tenantlayer;
 
import static org.assertj.core.api.Assertions.assertThat;
 
import io.tenantlayer.web.KeycloakOrganizationClaimResolver;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
 
/**
 * Covers both shapes Keycloak's {@code organization} claim can take, per
 * {@link KeycloakOrganizationClaimResolver}'s class doc.
 */
class KeycloakOrganizationClaimResolverTest {
 
    private final KeycloakOrganizationClaimResolver resolver = new KeycloakOrganizationClaimResolver();
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders");
 
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }
 
    private static void authenticate(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "none");
        claims.forEach(builder::claim);
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(builder.build(),
                        List.of(new SimpleGrantedAuthority("SCOPE_read"))));
    }
 
    @Test
    @DisplayName("reads the id from the rich map form when exactly one organization is present")
    void readsIdFromRichMapForm() {
        authenticate(Map.of("organization", Map.of("acme-corp", Map.of("id", "f8d3c4e1-abcd"))));
 
        assertThat(resolver.resolve(request)).contains("f8d3c4e1-abcd");
    }
 
    @Test
    @DisplayName("falls back to the org name in the plain array form")
    void fallsBackToNameInArrayForm() {
        authenticate(Map.of("organization", List.of("acme-corp")));
 
        assertThat(resolver.resolve(request)).contains("acme-corp");
    }
 
    @Test
    @DisplayName("refuses to guess when the rich map form has more than one organization")
    void multipleOrganizationsInMapFormYieldsNothing() {
        // Deliberately ordered so that plain HashMap iteration would NOT return the first
        // token entry, proving the resolver isn't relying on map order at all.
        Map<String, Object> multiOrg = new LinkedHashMap<>();
        multiOrg.put("acme-corp", Map.of("id", "f8d3c4e1-abcd"));
        multiOrg.put("globex", Map.of("id", "1a2b3c4d-efgh"));
        authenticate(Map.of("organization", multiOrg));
 
        assertThat(resolver.resolve(request)).isEmpty();
    }
 
    @Test
    @DisplayName("uses the first entry when the array form lists more than one organization")
    void multipleOrganizationsInArrayFormUsesFirst() {
        // Unlike the map form, a JSON array parses into a List, which preserves token order,
        // so picking the first element here is safe and deterministic.
        authenticate(Map.of("organization", List.of("acme-corp", "globex")));
 
        assertThat(resolver.resolve(request)).contains("acme-corp");
    }
 
    @Test
    @DisplayName("no authentication yields no tenant")
    void unauthenticatedYieldsNothing() {
        SecurityContextHolder.clearContext();
 
        assertThat(resolver.resolve(request)).isEmpty();
    }
 
    @Test
    @DisplayName("a non-JWT principal yields no tenant")
    void nonJwtPrincipalYieldsNothing() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user", "pw",
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
 
        assertThat(resolver.resolve(request)).isEmpty();
    }
 
    @Test
    @DisplayName("a token without the claim yields no tenant, it does not invent one")
    void missingClaimYieldsNothing() {
        authenticate(Map.of("sub", "user-1"));
 
        assertThat(resolver.resolve(request)).isEmpty();
    }
 
    @Test
    @DisplayName("an empty organization map yields no tenant")
    void emptyMapYieldsNothing() {
        authenticate(Map.of("organization", Map.of()));
 
        assertThat(resolver.resolve(request)).isEmpty();
    }
}
