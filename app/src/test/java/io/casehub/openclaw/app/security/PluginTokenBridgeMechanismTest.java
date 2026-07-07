package io.casehub.openclaw.app.security;

import org.junit.jupiter.api.Test;

import io.quarkus.security.identity.SecurityIdentity;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PluginTokenBridgeMechanismTest {

    private static final String DEFAULT_TENANCY = "278776f9-e1b0-46fb-9032-8bddebdcf9ce";
    private static final String TOKEN = "test-token";

    private final PluginTokenBridgeMechanism mechanism = new PluginTokenBridgeMechanism(TOKEN);

    @Test
    void validToken_stampstenancyIdAttribute() {
        RoutingContext ctx = pluginRequest("/openclaw/plugin/commit", "Bearer " + TOKEN);

        SecurityIdentity identity = mechanism.authenticate(ctx, null).await().indefinitely();

        assertThat(identity).isNotNull();
        assertThat((String) identity.getAttribute("tenancyId")).isEqualTo(DEFAULT_TENANCY);
    }

    @Test
    void validToken_stampsBridgeAttribute() {
        RoutingContext ctx = pluginRequest("/openclaw/plugin/commit", "Bearer " + TOKEN);

        SecurityIdentity identity = mechanism.authenticate(ctx, null).await().indefinitely();

        assertThat(identity).isNotNull();
        assertThat((Boolean) identity.getAttribute("casehub.plugin.bridge")).isTrue();
    }

    @Test
    void validToken_hasPluginRole() {
        RoutingContext ctx = pluginRequest("/openclaw/plugin/commit", "Bearer " + TOKEN);

        SecurityIdentity identity = mechanism.authenticate(ctx, null).await().indefinitely();

        assertThat(identity).isNotNull();
        assertThat(identity.getRoles()).containsExactly("openclaw-plugin");
    }

    @Test
    void wrongToken_returnsNull() {
        RoutingContext ctx = pluginRequest("/openclaw/plugin/commit", "Bearer wrong");

        SecurityIdentity identity = mechanism.authenticate(ctx, null).await().indefinitely();

        assertThat(identity).isNull();
    }

    @Test
    void nonPluginPath_returnsNull() {
        RoutingContext ctx = pluginRequest("/mcp", "Bearer " + TOKEN);

        SecurityIdentity identity = mechanism.authenticate(ctx, null).await().indefinitely();

        assertThat(identity).isNull();
    }

    @Test
    void noAuthHeader_returnsNull() {
        RoutingContext ctx = pluginRequest("/openclaw/plugin/commit", null);

        SecurityIdentity identity = mechanism.authenticate(ctx, null).await().indefinitely();

        assertThat(identity).isNull();
    }

    @Test
    void channelContextPath_validToken_authenticates() {
        RoutingContext ctx = pluginRequest("/channel-context/agent-1", "Bearer " + TOKEN);

        SecurityIdentity identity = mechanism.authenticate(ctx, null).await().indefinitely();

        assertThat(identity).isNotNull();
        assertThat((String) identity.getAttribute("tenancyId")).isEqualTo(DEFAULT_TENANCY);
    }

    private RoutingContext pluginRequest(String path, String authHeader) {
        RoutingContext ctx = mock(RoutingContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(ctx.request()).thenReturn(request);
        when(request.path()).thenReturn(path);
        when(request.getHeader("Authorization")).thenReturn(authHeader);
        return ctx;
    }
}
