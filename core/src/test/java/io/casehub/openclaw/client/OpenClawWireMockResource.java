package io.casehub.openclaw.client;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.util.Map;

public class OpenClawWireMockResource implements QuarkusTestResourceLifecycleManager {

    // Accessible from test class for stubbing and verification.
    // volatile: start() and test methods may run on different threads.
    // Single-class constraint: if a second @QuarkusTest class registers this resource,
    // start() overwrites INSTANCE (invalidating references held by the first class)
    // and resetAll() in one class will clear the other's stubs. Design for one class only.
    static volatile WireMockServer INSTANCE;

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
