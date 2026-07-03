package io.casehub.openclaw.app.security;

import java.util.Set;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.oidc.SecurityIdentityCurrentPrincipal;
import io.quarkus.security.identity.SecurityIdentity;

@RequestScoped
@Alternative
@Priority(150)
public class OpenClawCurrentPrincipal implements CurrentPrincipal {

    private static final String DEFAULT_TENANCY = "278776f9-e1b0-46fb-9032-8bddebdcf9ce";
    private static final String BRIDGE_ATTR = "casehub.plugin.bridge";

    private final SecurityIdentity identity;
    private final SecurityIdentityCurrentPrincipal oidcPrincipal;

    @Inject
    public OpenClawCurrentPrincipal(SecurityIdentity identity, SecurityIdentityCurrentPrincipal oidcPrincipal) {
        this.identity = identity;
        this.oidcPrincipal = oidcPrincipal;
    }

    @Override
    public String actorId() {
        if (isBridgeIdentity()) {
            return identity.getPrincipal().getName();
        }
        return oidcPrincipal.actorId();
    }

    @Override
    public Set<String> groups() {
        if (isBridgeIdentity()) {
            return identity.getRoles();
        }
        return oidcPrincipal.groups();
    }

    @Override
    public String tenancyId() {
        if (isBridgeIdentity()) {
            return DEFAULT_TENANCY;
        }
        return oidcPrincipal.tenancyId();
    }

    @Override
    public boolean isCrossTenantAdmin() {
        if (isBridgeIdentity()) {
            return false;
        }
        return oidcPrincipal.isCrossTenantAdmin();
    }

    private boolean isBridgeIdentity() {
        Boolean bridge = identity.getAttribute(BRIDGE_ATTR);
        return bridge != null && bridge;
    }
}
