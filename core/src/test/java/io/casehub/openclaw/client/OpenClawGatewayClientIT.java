package io.casehub.openclaw.client;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.notContaining;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@QuarkusTest
@QuarkusTestResource(OpenClawWireMockResource.class)
class OpenClawGatewayClientIT {

    static WireMockServer wireMock() {
        return OpenClawWireMockResource.INSTANCE;
    }

    @BeforeEach
    void resetWireMock() {
        wireMock().resetAll();
        // Clear any sessions registered by previous test methods —
        // hookClient is @ApplicationScoped (singleton) so session state persists across tests.
        hookClient.deregisterSession("finance-agent");
        hookClient.deregisterSession("home-agent");
    }

    @Inject
    OpenClawHookClient hookClient;

    // ── POST /hooks/agent ────────────────────────────────────────────────────

    @Test
    void invokeAgent_sendsCorrectJsonBodyAndBearerToken() {
        wireMock().stubFor(post(urlEqualTo("/hooks/agent"))
                .willReturn(aResponse().withStatus(200)));

        hookClient.registerSession("finance-agent", "session-key-xyz",
                "http://casehub.test/openclaw/delivery/channel/ch-001");
        hookClient.invoke("finance-agent", "Pull this month's transactions", null, 0);

        wireMock().verify(postRequestedFor(urlEqualTo("/hooks/agent"))
                .withHeader("Authorization",  equalTo("Bearer test-bearer-token"))
                .withHeader("Content-Type",   equalTo("application/json"))
                .withRequestBody(matchingJsonPath("$.agentId",     equalTo("finance-agent")))
                .withRequestBody(matchingJsonPath("$.message",     equalTo("Pull this month's transactions")))
                .withRequestBody(matchingJsonPath("$.deliver",     equalTo("webhook")))
                .withRequestBody(matchingJsonPath("$.to",          equalTo("http://casehub.test/openclaw/delivery/channel/ch-001")))
                .withRequestBody(matchingJsonPath("$.sessionName", equalTo("session-key-xyz")))
                .withRequestBody(matchingJsonPath("$.model",       equalTo("claude-haiku-4-5-20251001")))
                // integer field — use JSONPath predicate, not equalTo(String)
                .withRequestBody(matchingJsonPath("$[?(@.timeoutSeconds == 30)]")));
    }

    @Test
    void invokeAgent_gatewayReturns500_throwsInvocationException() {
        wireMock().stubFor(post(urlEqualTo("/hooks/agent"))
                .willReturn(aResponse().withStatus(500)));

        hookClient.registerSession("finance-agent", "key", "http://webhook");
        assertThatThrownBy(() -> hookClient.invoke("finance-agent", "msg", null, 0))
                .isInstanceOf(OpenClawInvocationException.class)
                .hasMessageContaining("500");
    }

    @Test
    void invokeAgent_nullSessionKey_sessionNameOmittedFromJson() {
        wireMock().stubFor(post(urlEqualTo("/hooks/agent"))
                .willReturn(aResponse().withStatus(200)));

        hookClient.registerSession("home-agent", null, "http://webhook/channel/2");
        hookClient.invoke("home-agent", "run task", null, 0);

        // @JsonInclude(NON_NULL) on sessionName — the key must be absent from the body
        wireMock().verify(postRequestedFor(urlEqualTo("/hooks/agent"))
                .withRequestBody(notContaining("\"sessionName\"")));
    }

    // ── POST /hooks/wake ─────────────────────────────────────────────────────

    @Test
    void wakeAgent_sendsCorrectJsonBodyAndBearerToken() {
        wireMock().stubFor(post(urlEqualTo("/hooks/wake"))
                .willReturn(aResponse().withStatus(200)));

        hookClient.wake("home-agent", "Time to check the boiler");

        wireMock().verify(postRequestedFor(urlEqualTo("/hooks/wake"))
                .withHeader("Authorization", equalTo("Bearer test-bearer-token"))
                .withRequestBody(matchingJsonPath("$.agentId", equalTo("home-agent")))
                .withRequestBody(matchingJsonPath("$.message", equalTo("Time to check the boiler"))));
    }

    @Test
    void wakeAgent_gatewayReturns500_throwsInvocationException() {
        wireMock().stubFor(post(urlEqualTo("/hooks/wake"))
                .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> hookClient.wake("home-agent", "msg"))
                .isInstanceOf(OpenClawInvocationException.class)
                .hasMessageContaining("500");
    }
}
