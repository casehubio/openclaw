package io.casehub.openclaw.client;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenClawHookClientTest {

    private OpenClawGatewayClient gatewayClient;
    private OpenClawClientConfig config;
    private OpenClawHookClient hookClient;

    @BeforeEach
    void setup() {
        gatewayClient = mock(OpenClawGatewayClient.class);
        config = mockConfig("claude-opus-4-5", 120);
        hookClient = new OpenClawHookClient(gatewayClient, config);
    }

    // ── Session registry ─────────────────────────────────────────────────────

    @Test
    void registerSession_thenFindSession_returnsSession() {
        hookClient.registerSession("finance-agent", "key-abc", "http://webhook/channel/1");
        assertThat(hookClient.findSession("finance-agent"))
                .isPresent()
                .hasValueSatisfying(s -> {
                    assertThat(s.agentId()).isEqualTo("finance-agent");
                    assertThat(s.sessionKey()).isEqualTo("key-abc");
                    assertThat(s.webhookUrl()).isEqualTo("http://webhook/channel/1");
                });
    }

    @Test
    void findSession_noRegistration_returnsEmpty() {
        assertThat(hookClient.findSession("unknown-agent")).isEmpty();
    }

    @Test
    void deregisterSession_removesMapping() {
        hookClient.registerSession("finance-agent", "key-abc", "http://webhook/channel/1");
        hookClient.deregisterSession("finance-agent");
        assertThat(hookClient.findSession("finance-agent")).isEmpty();
    }

    @Test
    void registerSession_secondRegistration_overwritesFirst() {
        hookClient.registerSession("finance-agent", "key-1", "http://webhook/channel/1");
        hookClient.registerSession("finance-agent", "key-2", "http://webhook/channel/2");
        assertThat(hookClient.findSession("finance-agent"))
                .hasValueSatisfying(s -> assertThat(s.sessionKey()).isEqualTo("key-2"));
    }

    // ── invoke ───────────────────────────────────────────────────────────────
    // Note: these tests use a mock that returns a Response object on 5xx.
    // In production, Quarkus REST Client throws WebApplicationException instead.
    // The catch(WebApplicationException) branch in invoke() and wake() is covered
    // by OpenClawGatewayClientIT (real HTTP transport via WireMock), not here.

    @Test
    void invoke_noRegisteredSession_throwsInvocationException() {
        assertThatThrownBy(() -> hookClient.invoke("unknown-agent", "hello", null, 0))
                .isInstanceOf(OpenClawInvocationException.class)
                .hasMessageContaining("unknown-agent");
    }

    @Test
    void invoke_registeredSession_callsGatewayWithCorrectRequest() {
        // Assign before when() to avoid nested Mockito stubbing inside thenReturn() argument
        Response ok = mockResponse(200);
        when(gatewayClient.invokeAgent(any())).thenReturn(ok);

        hookClient.registerSession("finance-agent", "session-key-1",
                "http://casehub.test/delivery/channel/abc");
        hookClient.invoke("finance-agent", "Pull transactions", "claude-haiku-4-5-20251001", 45);

        ArgumentCaptor<AgentInvocationRequest> captor =
                ArgumentCaptor.forClass(AgentInvocationRequest.class);
        verify(gatewayClient).invokeAgent(captor.capture());
        AgentInvocationRequest req = captor.getValue();
        assertThat(req.agentId()).isEqualTo("finance-agent");
        assertThat(req.message()).isEqualTo("Pull transactions");
        assertThat(req.deliver()).isEqualTo("webhook");
        assertThat(req.to()).isEqualTo("http://casehub.test/delivery/channel/abc");
        assertThat(req.model()).isEqualTo("claude-haiku-4-5-20251001");
        assertThat(req.timeoutSeconds()).isEqualTo(45);
        assertThat(req.sessionName()).isEqualTo("session-key-1");
    }

    @Test
    void invoke_nullModel_usesConfigDefault() {
        Response ok = mockResponse(200);
        when(gatewayClient.invokeAgent(any())).thenReturn(ok);
        hookClient.registerSession("finance-agent", "key", "http://webhook");
        hookClient.invoke("finance-agent", "msg", null, 30);

        ArgumentCaptor<AgentInvocationRequest> captor =
                ArgumentCaptor.forClass(AgentInvocationRequest.class);
        verify(gatewayClient).invokeAgent(captor.capture());
        assertThat(captor.getValue().model()).isEqualTo("claude-opus-4-5");
    }

    @Test
    void invoke_blankModel_usesConfigDefault() {
        Response ok = mockResponse(200);
        when(gatewayClient.invokeAgent(any())).thenReturn(ok);
        hookClient.registerSession("finance-agent", "key", "http://webhook");
        hookClient.invoke("finance-agent", "msg", "  ", 30);

        ArgumentCaptor<AgentInvocationRequest> captor =
                ArgumentCaptor.forClass(AgentInvocationRequest.class);
        verify(gatewayClient).invokeAgent(captor.capture());
        assertThat(captor.getValue().model()).isEqualTo("claude-opus-4-5");
    }

    @Test
    void invoke_zeroTimeout_usesConfigDefault() {
        Response ok = mockResponse(200);
        when(gatewayClient.invokeAgent(any())).thenReturn(ok);
        hookClient.registerSession("finance-agent", "key", "http://webhook");
        hookClient.invoke("finance-agent", "msg", "claude-opus-4-5", 0);

        ArgumentCaptor<AgentInvocationRequest> captor =
                ArgumentCaptor.forClass(AgentInvocationRequest.class);
        verify(gatewayClient).invokeAgent(captor.capture());
        assertThat(captor.getValue().timeoutSeconds()).isEqualTo(120);
    }

    @Test
    void invoke_gatewayReturns5xx_throwsInvocationException() {
        Response err = mockResponse(503);
        when(gatewayClient.invokeAgent(any())).thenReturn(err);
        hookClient.registerSession("finance-agent", "key", "http://webhook");
        assertThatThrownBy(() -> hookClient.invoke("finance-agent", "msg", null, 0))
                .isInstanceOf(OpenClawInvocationException.class)
                .hasMessageContaining("503");
    }

    @Test
    void invoke_withExplicitDeliveryUrl_usesProvidedUrlNotSessionUrl() {
        // arrange
        OpenClawGatewayClient gatewayClient = mock(OpenClawGatewayClient.class);
        OpenClawClientConfig config = mockConfig("claude-opus-4-5", 120);
        OpenClawHookClient client = new OpenClawHookClient(gatewayClient, config);

        Response okResponse = mockResponse(200);
        when(gatewayClient.invokeAgent(any(AgentInvocationRequest.class))).thenReturn(okResponse);

        client.registerSession("my-agent", "sk-abc", "http://host/channel/123");

        // act — invoke with a DIFFERENT delivery URL (the oversight endpoint)
        client.invoke("my-agent", "approve this?", "claude-opus-4-5", 30,
                "http://host/openclaw/delivery/oversight/gate-uuid");

        // assert — gatewayClient received the explicit URL, not the session's registered URL
        ArgumentCaptor<AgentInvocationRequest> captor = ArgumentCaptor.forClass(AgentInvocationRequest.class);
        verify(gatewayClient).invokeAgent(captor.capture());
        assertThat(captor.getValue().to()).isEqualTo("http://host/openclaw/delivery/oversight/gate-uuid");
        assertThat(captor.getValue().message()).isEqualTo("approve this?");
        assertThat(captor.getValue().agentId()).isEqualTo("my-agent");
        assertThat(captor.getValue().deliver()).isEqualTo("webhook");
    }

    // ── invokeDirect ──────────────────────────────────────────────────────────

    @Test
    void invokeDirect_noSessionRequired() {
        Response ok = mockResponse(200);
        when(gatewayClient.invokeAgent(any())).thenReturn(ok);

        hookClient.invokeDirect("health-agent", "Book appointment", null, 30,
                "https://casehub.internal/openclaw/direct-call/abc-123");

        ArgumentCaptor<AgentInvocationRequest> captor =
                ArgumentCaptor.forClass(AgentInvocationRequest.class);
        verify(gatewayClient).invokeAgent(captor.capture());
        AgentInvocationRequest req = captor.getValue();
        assertThat(req.agentId()).isEqualTo("health-agent");
        assertThat(req.message()).isEqualTo("Book appointment");
        assertThat(req.deliver()).isEqualTo("webhook");
        assertThat(req.to()).isEqualTo("https://casehub.internal/openclaw/direct-call/abc-123");
        assertThat(req.sessionName()).isNull();
    }

    @Test
    void invokeDirect_nullModel_usesConfigDefault() {
        Response ok = mockResponse(200);
        when(gatewayClient.invokeAgent(any())).thenReturn(ok);

        hookClient.invokeDirect("health-agent", "msg", null, 30,
                "https://casehub.internal/openclaw/direct-call/corr-1");

        ArgumentCaptor<AgentInvocationRequest> captor =
                ArgumentCaptor.forClass(AgentInvocationRequest.class);
        verify(gatewayClient).invokeAgent(captor.capture());
        assertThat(captor.getValue().model()).isEqualTo("claude-opus-4-5");
    }

    @Test
    void invokeDirect_gatewayReturns5xx_throwsInvocationException() {
        Response err = mockResponse(503);
        when(gatewayClient.invokeAgent(any())).thenReturn(err);

        assertThatThrownBy(() -> hookClient.invokeDirect("health-agent", "msg", null, 30,
                "https://casehub.internal/openclaw/direct-call/corr-1"))
                .isInstanceOf(OpenClawInvocationException.class)
                .hasMessageContaining("503");
    }

    // ── wake ─────────────────────────────────────────────────────────────────

    @Test
    void wake_callsGatewayWithoutRequiringSession() {
        Response ok = mockResponse(200);
        when(gatewayClient.wakeAgent(any())).thenReturn(ok);
        hookClient.wake("home-agent", "Check boiler");

        ArgumentCaptor<AgentWakeRequest> captor =
                ArgumentCaptor.forClass(AgentWakeRequest.class);
        verify(gatewayClient).wakeAgent(captor.capture());
        assertThat(captor.getValue().agentId()).isEqualTo("home-agent");
        assertThat(captor.getValue().message()).isEqualTo("Check boiler");
    }

    @Test
    void wake_gatewayReturns5xx_throwsInvocationException() {
        Response err = mockResponse(500);
        when(gatewayClient.wakeAgent(any())).thenReturn(err);
        assertThatThrownBy(() -> hookClient.wake("home-agent", "msg"))
                .isInstanceOf(OpenClawInvocationException.class)
                .hasMessageContaining("500");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Create a Response mock with the given status code.
     *
     * IMPORTANT: always assign the result to a local variable BEFORE passing it
     * to when(...).thenReturn(). Java evaluates method arguments left-to-right, so
     * calling mockResponse() inside thenReturn() would start a nested Mockito stubbing
     * chain (when(r.getStatus())...) while the outer when() chain is still open —
     * triggering UnfinishedStubbingException.
     *
     * Correct:  Response ok = mockResponse(200); when(mock.method()).thenReturn(ok);
     * Wrong:    when(mock.method()).thenReturn(mockResponse(200));
     */
    private static Response mockResponse(int status) {
        Response r = mock(Response.class);
        when(r.getStatus()).thenReturn(status);
        return r;
    }

    private static OpenClawClientConfig mockConfig(String model, int timeoutSecs) {
        OpenClawClientConfig config = mock(OpenClawClientConfig.class);
        OpenClawClientConfig.Agent agent = mock(OpenClawClientConfig.Agent.class);
        // lenient() suppresses "unnecessary stubbing" for @BeforeEach stubs
        // not used in every test (session registry tests don't call config.agent()).
        lenient().when(config.agent()).thenReturn(agent);
        lenient().when(agent.defaultModel()).thenReturn(model);
        lenient().when(agent.defaultTimeoutSeconds()).thenReturn(timeoutSecs);
        return config;
    }
}
