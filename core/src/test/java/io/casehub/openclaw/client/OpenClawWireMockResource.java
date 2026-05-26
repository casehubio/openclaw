package io.casehub.openclaw.client;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.util.Map;

public class OpenClawWireMockResource implements QuarkusTestResourceLifecycleManager {

    // Accessible from test class for stubbing and verification
    static WireMockServer INSTANCE;

    private WireMockServer server;

    @Override
    public Map<String, String> start() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        INSTANCE = server;
        String url = "http://localhost:" + server.port();
        return Map.of(
                "quarkus.rest-client.openclaw-gateway.url", url,
                // casehub.openclaw.gateway.url is required by @ConfigMapping validation;
                // it must match the REST client URL so both point at WireMock
                "casehub.openclaw.gateway.url", url,
                "casehub.openclaw.gateway.bearer-token", "test-bearer-token"
        );
    }

    @Override
    public void stop() {
        if (server != null) server.stop();
    }
}
