package io.tenantlayer;

import static org.assertj.core.api.Assertions.assertThat;

import io.tenantlayer.web.KeycloakOrganizationClaimResolver;
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
    @DisplayName("reads the id from the rich map form")
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
