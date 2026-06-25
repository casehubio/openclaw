package io.casehub.openclaw.casehub;

import io.casehub.openclaw.client.OpenClawHookClient;
import io.casehub.openclaw.client.OpenClawInvocationException;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentSessionConfig;
import io.casehub.platform.agent.AgentSessionInit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OpenClawAgentProviderTest {

    @Test
    void invoke_callsInvokeDirectWithCorrectDeliveryUrl() {
        DirectCallBridge bridge = new DirectCallBridge();
        OpenClawHookClient hookClient = mock(OpenClawHookClient.class);
        OpenClawAgentProvider provider = new OpenClawAgentProvider(
                bridge, hookClient, "health-agent", "https://casehub.internal");

        AgentSessionConfig config = new AgentSessionConfig(
                "You are a health agent", "Book appointment with Dr Smith",
                List.of(), Duration.ofSeconds(30), "test-corr-id");

        provider.invoke(config).subscribe().with(e -> {}, e -> {});

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(hookClient).invokeDirect(eq("health-agent"), anyString(),
                isNull(), eq(30), urlCaptor.capture());
        assertThat(urlCaptor.getValue()).startsWith(
                "https://casehub.internal/openclaw/direct-call/");
    }

    @Test
    void invoke_usesCorrelationIdFromConfig() {
        DirectCallBridge bridge = new DirectCallBridge();
        OpenClawHookClient hookClient = mock(OpenClawHookClient.class);
        OpenClawAgentProvider provider = new OpenClawAgentProvider(
                bridge, hookClient, "health-agent", "https://casehub.internal");

        AgentSessionConfig config = new AgentSessionConfig(
                "sys", "user", List.of(), Duration.ofSeconds(30), "my-corr-id");

        provider.invoke(config).subscribe().with(e -> {}, e -> {});

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(hookClient).invokeDirect(anyString(), anyString(),
                isNull(), anyInt(), urlCaptor.capture());
        assertThat(urlCaptor.getValue()).endsWith("/my-corr-id");
    }

    @Test
    void invoke_combinesSystemPromptAndUserPrompt() {
        DirectCallBridge bridge = new DirectCallBridge();
        OpenClawHookClient hookClient = mock(OpenClawHookClient.class);
        OpenClawAgentProvider provider = new OpenClawAgentProvider(
                bridge, hookClient, "health-agent", "https://casehub.internal");

        AgentSessionConfig config = new AgentSessionConfig(
                "System prompt here", "User prompt here",
                List.of(), Duration.ofSeconds(30), null);

        provider.invoke(config).subscribe().with(e -> {}, e -> {});

        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(hookClient).invokeDirect(anyString(), msgCaptor.capture(),
                isNull(), anyInt(), anyString());
        String message = msgCaptor.getValue();
        assertThat(message).contains("System prompt here");
        assertThat(message).contains("User prompt here");
    }

    @Test
    void invoke_emitsTextDeltaOnFutureCompletion() {
        DirectCallBridge bridge = new DirectCallBridge();
        OpenClawHookClient hookClient = mock(OpenClawHookClient.class);
        OpenClawAgentProvider provider = new OpenClawAgentProvider(
                bridge, hookClient, "health-agent", "https://casehub.internal");

        doAnswer(inv -> {
            String url = inv.getArgument(4);
            String corrId = url.substring(url.lastIndexOf('/') + 1);
            bridge.complete(corrId, "{\"result\":\"ok\"}");
            return null;
        }).when(hookClient).invokeDirect(anyString(), anyString(),
                isNull(), anyInt(), anyString());

        AgentSessionConfig config = AgentSessionConfig.of("sys", "user", Duration.ofSeconds(5));

        List<AgentEvent> events = provider.invoke(config)
                .collect().asList()
                .await().atMost(Duration.ofSeconds(5));

        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(AgentEvent.TextDelta.class);
        assertThat(((AgentEvent.TextDelta) events.get(0)).text()).isEqualTo("{\"result\":\"ok\"}");
    }

    @Test
    void invoke_invocationException_failsMulti() {
        DirectCallBridge bridge = new DirectCallBridge();
        OpenClawHookClient hookClient = mock(OpenClawHookClient.class);
        doThrow(new OpenClawInvocationException("HTTP 503"))
                .when(hookClient).invokeDirect(anyString(), anyString(),
                        isNull(), anyInt(), anyString());

        OpenClawAgentProvider provider = new OpenClawAgentProvider(
                bridge, hookClient, "health-agent", "https://casehub.internal");
        AgentSessionConfig config = AgentSessionConfig.of("sys", "user", Duration.ofSeconds(5));

        assertThatThrownBy(() ->
                provider.invoke(config).collect().asList()
                        .await().atMost(Duration.ofSeconds(5)))
                .isInstanceOf(OpenClawInvocationException.class);
    }

    @Test
    void openSession_throwsUnsupported() {
        DirectCallBridge bridge = new DirectCallBridge();
        OpenClawHookClient hookClient = mock(OpenClawHookClient.class);
        OpenClawAgentProvider provider = new OpenClawAgentProvider(
                bridge, hookClient, "health-agent", "https://casehub.internal");

        assertThatThrownBy(() -> provider.openSession(AgentSessionInit.of("sys")))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
