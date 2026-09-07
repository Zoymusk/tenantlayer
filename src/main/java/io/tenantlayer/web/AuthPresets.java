package io.tenantlayer.web;

/**
 * Prebuilt {@link JwtClaimTenantResolver} configuration for identity providers whose
 * tenant/organization id is a flat top-level claim.
 *
 * <p>Keycloak nests its organization id inside an object rather than exposing it as a flat
 * claim, so it needs different extraction logic — see {@link KeycloakOrganizationClaimResolver}.
 */
public final class AuthPresets {

    /** Auth0: Organizations feature puts the org id in a flat {@code org_id} claim. */
    public static final String AUTH0_CLAIM = "org_id";

    private AuthPresets() {
    }

    /**
     * Resolver preconfigured for Auth0's Organizations feature.
     *
     * @see <a href="https://auth0.com/docs/manage-users/organizations/using-tokens">Auth0: Work with Tokens and Organizations</a>
     */
    public static JwtClaimTenantResolver forAuth0() {
        return new JwtClaimTenantResolver(AUTH0_CLAIM);
    }
}
