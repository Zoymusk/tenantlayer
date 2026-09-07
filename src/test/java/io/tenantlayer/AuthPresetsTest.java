package io.tenantlayer;

import static org.assertj.core.api.Assertions.assertThat;

import io.tenantlayer.web.AuthPresets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class AuthPresetsTest {

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
    @DisplayName("Auth0 preset reads the org_id claim")
    void auth0PresetReadsOrgId() {
        authenticate(Map.of("org_id", "org_vIK75NKFvaozQsFy"));

        assertThat(AuthPresets.forAuth0().resolve(request)).contains("org_vIK75NKFvaozQsFy");
    }

    @Test
    @DisplayName("Auth0 preset yields nothing when org_id is absent")
    void auth0PresetMissingClaimYieldsNothing() {
        authenticate(Map.of("sub", "user-1"));

        assertThat(AuthPresets.forAuth0().resolve(request)).isEmpty();
    }
}
