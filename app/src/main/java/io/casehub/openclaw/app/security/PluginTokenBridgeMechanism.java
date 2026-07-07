package io.casehub.openclaw.app.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.casehub.openclaw.app.OpenClawGroups;
import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.quarkus.vertx.http.runtime.security.HttpCredentialTransport;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;

@ApplicationScoped
public class PluginTokenBridgeMechanism implements HttpAuthenticationMechanism {

    private static final String DEFAULT_TENANCY = "278776f9-e1b0-46fb-9032-8bddebdcf9ce";

    private final byte[] configuredTokenBytes;

    @Inject
    public PluginTokenBridgeMechanism(
            @ConfigProperty(name = "casehub.openclaw.plugin.bearer-token") String configuredToken) {
        this.configuredTokenBytes = configuredToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public Uni<SecurityIdentity> authenticate(RoutingContext context,
                                              IdentityProviderManager identityProviderManager) {
        // Only handle /openclaw/plugin/* with a valid Bearer token.
        // Returns null for everything else — non-plugin paths, missing token, wrong token.
        // @RolesAllowed(PLUGIN) on the resource handles rejection (401 anonymous, 403 wrong role).
        // Never throw AuthenticationFailedException — it triggers OIDC challenge flow which
        // connects to the (absent) OIDC server and returns 500 instead of 401.
        String path = context.request().path();
        if (!path.startsWith("/openclaw/plugin/") && !path.startsWith("/channel-context/")) {
            return Uni.createFrom().nullItem();
        }

        String authHeader = context.request().getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return Uni.createFrom().nullItem();
        }

        String token = authHeader.substring(7).trim();
        if (!MessageDigest.isEqual(configuredTokenBytes, token.getBytes(StandardCharsets.UTF_8))) {
            return Uni.createFrom().nullItem();
        }

        SecurityIdentity identity = QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal("openclaw-plugin"))
                .addRole(OpenClawGroups.PLUGIN)
                .addAttribute("casehub.plugin.bridge", true)
                .addAttribute("tenancyId", DEFAULT_TENANCY)
                .build();

        return Uni.createFrom().item(identity);
    }

    @Override
    public Uni<ChallengeData> getChallenge(RoutingContext context) {
        return Uni.createFrom().item(
                new ChallengeData(401, "WWW-Authenticate", "Bearer realm=\"openclaw-plugin\""));
    }

    @Override
    public Uni<HttpCredentialTransport> getCredentialTransport(RoutingContext context) {
        // Return null: "this mechanism cannot interfere with other mechanisms" (Javadoc).
        // Declaring Type.AUTHORIZATION "bearer" conflicts with OIDC's Bearer transport,
        // breaking @TestSecurity and causing mechanism-selection ambiguity.
        // Path guard in authenticate() provides the isolation instead.
        return Uni.createFrom().nullItem();
    }

    @Override
    public Set<Class<? extends AuthenticationRequest>> getCredentialTypes() {
        return Set.of();
    }
}
