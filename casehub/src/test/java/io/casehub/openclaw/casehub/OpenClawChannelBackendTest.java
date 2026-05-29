package io.casehub.openclaw.casehub;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import io.casehub.api.model.CaseChannel;
import io.casehub.openclaw.client.OpenClawClientConfig;
import io.casehub.openclaw.client.OpenClawHookClient;
import io.casehub.openclaw.client.OpenClawInvocationException;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.gateway.ChannelInitialisedEvent;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class OpenClawChannelBackendTest {

    OpenClawAgentRegistry registry;
    OpenClawHookClient hookClient;
    ChannelGateway gateway;
    OpenClawClientConfig clientConfig;
    OpenClawChannelBackend backend;

    UUID caseId = UUID.randomUUID();
    UUID channelId = UUID.randomUUID();

    @BeforeEach
    void setup() {
        registry = new OpenClawAgentRegistry();
        hookClient = mock(OpenClawHookClient.class);
        gateway = mock(ChannelGateway.class);
        clientConfig = config("http://localhost:8080", "claude-opus-4-5", 120);
        backend = new OpenClawChannelBackend(registry, hookClient, gateway, clientConfig);
    }

    // ── registration via ChannelInitialisedEvent ───────────────────────────────

    @Test
    void onChannelInitialised_caseChannel_registersBackend() {
        backend.onChannelInitialised(new ChannelInitialisedEvent(channelId, "case-" + caseId + "/work"));
        verify(gateway).registerBackend(eq(channelId), eq(backend), eq("agent"));
    }

    @Test
    void onChannelInitialised_nonCaseChannel_doesNotRegister() {
        backend.onChannelInitialised(new ChannelInitialisedEvent(channelId, "some-other-channel"));
        verify(gateway, never()).registerBackend(any(), any(), any());
    }

    // ── COMMAND invocation ────────────────────────────────────────────────────

    @Test
    void post_command_invokesAgent() {
        registry.register("finance-agent", caseId, "finance-agent");
        ChannelRef ref = new ChannelRef(channelId, "case-" + caseId + "/work");
        OutboundMessage msg = command("Analyse this PR");

        backend.post(ref, msg);

        verify(hookClient).registerSession(eq("finance-agent"), eq("finance-agent"),
                eq("http://localhost:8080/channel/" + channelId));
        verify(hookClient).invoke(eq("finance-agent"), eq("Analyse this PR"),
                eq("claude-opus-4-5"), eq(120));
    }

    @ParameterizedTest
    @EnumSource(value = MessageType.class, mode = EnumSource.Mode.EXCLUDE, names = {"COMMAND"})
    void post_nonCommand_doesNotInvokeAgent(MessageType type) {
        registry.register("finance-agent", caseId, "finance-agent");
        ChannelRef ref = new ChannelRef(channelId, "case-" + caseId + "/work");
        OutboundMessage msg = new OutboundMessage(UUID.randomUUID(), "engine", type,
                "content", null, null, ActorType.AGENT);

        backend.post(ref, msg);
        verify(hookClient, never()).invoke(any(), any(), any(), any(Integer.class));
    }

    @Test
    void post_noAgentRegisteredForCase_noOp() {
        ChannelRef ref = new ChannelRef(channelId, "case-" + caseId + "/work");
        assertThatCode(() -> backend.post(ref, command("content"))).doesNotThrowAnyException();
        verify(hookClient, never()).invoke(any(), any(), any(), any(Integer.class));
    }

    @Test
    void post_invokeThrows_exceptionCaughtNotPropagated() {
        registry.register("finance-agent", caseId, "finance-agent");
        doThrow(new OpenClawInvocationException("network error"))
                .when(hookClient).invoke(any(), any(), any(), any(Integer.class));
        ChannelRef ref = new ChannelRef(channelId, "case-" + caseId + "/work");

        assertThatCode(() -> backend.post(ref, command("content"))).doesNotThrowAnyException();
    }

    @Test
    void post_nonCaseChannelName_noOp() {
        registry.register("finance-agent", caseId, "finance-agent");
        ChannelRef ref = new ChannelRef(channelId, "non-case-channel");

        assertThatCode(() -> backend.post(ref, command("content"))).doesNotThrowAnyException();
        verify(hookClient, never()).invoke(any(), any(), any(), any(Integer.class));
    }

    // ── identity ──────────────────────────────────────────────────────────────

    @Test
    void backendId_isOpenclaw() {
        assertThat(backend.backendId()).isEqualTo("openclaw");
    }

    @Test
    void actorType_isAgent() {
        assertThat(backend.actorType()).isEqualTo(ActorType.AGENT);
    }

    // ── extractCaseId ─────────────────────────────────────────────────────────

    @Test
    void extractCaseId_validCaseChannel_returnsUUID() {
        UUID id = UUID.randomUUID();
        assertThat(backend.extractCaseId("case-" + id + "/work")).isEqualTo(id);
    }

    @Test
    void extractCaseId_nonCaseChannel_returnsNull() {
        assertThat(backend.extractCaseId("other-channel")).isNull();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private OutboundMessage command(String content) {
        return new OutboundMessage(UUID.randomUUID(), "engine", MessageType.COMMAND,
                content, null, null, ActorType.AGENT);
    }

    private OpenClawClientConfig config(String baseUrl, String model, int timeout) {
        return new OpenClawClientConfig() {
            @Override public Gateway gateway() { return new Gateway() {
                @Override public String url() { return "http://openclaw"; }
                @Override public String bearerToken() { return "tok"; }
            }; }
            @Override public Delivery delivery() { return () -> baseUrl; }
            @Override public Agent agent() { return new Agent() {
                @Override public String defaultModel() { return model; }
                @Override public int defaultTimeoutSeconds() { return timeout; }
            }; }
        };
    }
}
