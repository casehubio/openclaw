package io.casehub.openclaw.app.security;

import java.util.Set;

import org.junit.jupiter.api.Test;

import io.casehub.openclaw.app.OpenClawGroups;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.oidc.SecurityIdentityCurrentPrincipal;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenClawCurrentPrincipalTest {

    private static final String DEFAULT_TENANCY = "278776f9-e1b0-46fb-9032-8bddebdcf9ce";

    @Test
    void bridgeIdentity_tenancyId_returnsDefault() {
        SecurityIdentity identity = QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal("openclaw-plugin"))
                .addRole(OpenClawGroups.PLUGIN)
                .addAttribute("casehub.plugin.bridge", true)
                .build();
        SecurityIdentityCurrentPrincipal oidc = mock(SecurityIdentityCurrentPrincipal.class);

        OpenClawCurrentPrincipal principal = new OpenClawCurrentPrincipal(identity, oidc);

        assertEquals(DEFAULT_TENANCY, principal.tenancyId());
    }

    @Test
    void bridgeIdentity_actorId_returnsPrincipalName() {
        SecurityIdentity identity = QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal("openclaw-plugin"))
                .addRole(OpenClawGroups.PLUGIN)
                .addAttribute("casehub.plugin.bridge", true)
                .build();
        SecurityIdentityCurrentPrincipal oidc = mock(SecurityIdentityCurrentPrincipal.class);

        OpenClawCurrentPrincipal principal = new OpenClawCurrentPrincipal(identity, oidc);

        assertEquals("openclaw-plugin", principal.actorId());
    }

    @Test
    void bridgeIdentity_groups_returnsIdentityRoles() {
        SecurityIdentity identity = QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal("openclaw-plugin"))
                .addRole(OpenClawGroups.PLUGIN)
                .addAttribute("casehub.plugin.bridge", true)
                .build();
        SecurityIdentityCurrentPrincipal oidc = mock(SecurityIdentityCurrentPrincipal.class);

        OpenClawCurrentPrincipal principal = new OpenClawCurrentPrincipal(identity, oidc);

        assertEquals(Set.of(OpenClawGroups.PLUGIN), principal.groups());
    }

    @Test
    void bridgeIdentity_isCrossTenantAdmin_returnsFalse() {
        SecurityIdentity identity = QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal("openclaw-plugin"))
                .addRole(OpenClawGroups.PLUGIN)
                .addAttribute("casehub.plugin.bridge", true)
                .build();
        SecurityIdentityCurrentPrincipal oidc = mock(SecurityIdentityCurrentPrincipal.class);

        OpenClawCurrentPrincipal principal = new OpenClawCurrentPrincipal(identity, oidc);

        assertFalse(principal.isCrossTenantAdmin());
    }

    @Test
    void nonBridgeIdentity_delegatesToOidc() {
        SecurityIdentity identity = QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal("user@example.com"))
                .addRole("some-role")
                .build();
        SecurityIdentityCurrentPrincipal oidc = mock(SecurityIdentityCurrentPrincipal.class);
        when(oidc.tenancyId()).thenReturn("tenant-123");
        when(oidc.actorId()).thenReturn("user@example.com");
        when(oidc.groups()).thenReturn(Set.of("some-role"));
        when(oidc.isCrossTenantAdmin()).thenReturn(false);

        OpenClawCurrentPrincipal principal = new OpenClawCurrentPrincipal(identity, oidc);

        assertEquals("tenant-123", principal.tenancyId());
        assertEquals("user@example.com", principal.actorId());
        assertEquals(Set.of("some-role"), principal.groups());
        assertFalse(principal.isCrossTenantAdmin());
        verify(oidc).tenancyId();
        verify(oidc).actorId();
    }

    @Test
    void anonymousIdentity_delegatesToOidc() {
        SecurityIdentity identity = QuarkusSecurityIdentity.builder()
                .setAnonymous(true)
                .setPrincipal(new QuarkusPrincipal("anonymous"))
                .build();
        SecurityIdentityCurrentPrincipal oidc = mock(SecurityIdentityCurrentPrincipal.class);
        when(oidc.tenancyId()).thenReturn(DEFAULT_TENANCY);
        when(oidc.actorId()).thenReturn("anonymous");

        OpenClawCurrentPrincipal principal = new OpenClawCurrentPrincipal(identity, oidc);

        assertEquals(DEFAULT_TENANCY, principal.tenancyId());
        verify(oidc).tenancyId();
    }
}
