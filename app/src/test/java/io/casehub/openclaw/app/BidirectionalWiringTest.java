package io.casehub.openclaw.app;

import java.util.List;
import java.util.UUID;

import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.casehub.openclaw.casehub.OpenClawAgentRegistry;
import io.casehub.openclaw.casehub.OpenClawChannelBackend;
import io.casehub.openclaw.client.AgentInvocationRequest;
import io.casehub.openclaw.client.OpenClawGatewayClient;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.gateway.ChannelInitialisedEvent;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.message.Message;
import io.casehub.qhorus.runtime.message.MessageService;
import io.casehub.qhorus.runtime.store.MessageStore;
import io.casehub.qhorus.runtime.store.query.MessageQuery;
import io.casehub.platform.api.identity.ActorType;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end integration test for the bidirectional Qhorus-OpenClaw round trip.
 *
 * <p>Uses real CDI wiring (ChannelService, OversightGateService,
 * OpenClawChannelBackend) with InMemory stores from casehub-qhorus-testing. Only
 * {@link OpenClawGatewayClient} (external HTTP) is mocked.
 *
 * <p>{@link MessageService} is a spy: all methods delegate to the real
 * implementation except {@code findAllByCorrelationId}, which is overridden to
 * scan the InMemory {@link MessageStore} directly. This is necessary because
 * the real {@code findAllByCorrelationId} uses Panache static queries that
 * hit H2, while the InMemory store doesn't persist to JPA.
 *
 * <p>Flow under test:
 * <ol>
 *   <li>COMMAND on work channel → OpenClawChannelBackend → OpenClawHookClient → gateway mock</li>
 *   <li>Delivery webhook → OversightGateService.evaluate() → STATUS on work channel (archival)</li>
 * </ol>
 */
@QuarkusTest
class BidirectionalWiringTest {

    @InjectMock
    @RestClient
    OpenClawGatewayClient gatewayClient;

    @InjectSpy
    MessageService messageService;

    @Inject
    ChannelService channelService;

    @Inject
    MessageStore messageStore;

    @Inject
    OpenClawAgentRegistry registry;

    @Inject
    OpenClawChannelBackend channelBackend;

    @Inject
    Event<ChannelInitialisedEvent> channelInitEvent;

    UUID caseId;
    UUID workChannelId;
    UUID oversightChannelId;
    String workChannelName;
    String oversightChannelName;

    @BeforeEach
    void setup() {
        caseId = UUID.randomUUID();
        workChannelName = "case-" + caseId + "/work";
        oversightChannelName = "case-" + caseId + "/oversight";

        // Create channels via real ChannelService (InMemory store)
        var workChannel = channelService.create(workChannelName, "Work channel", ChannelSemantic.APPEND, null);
        workChannelId = workChannel.id;

        var oversightChannel = channelService.create(oversightChannelName, "Oversight channel", ChannelSemantic.APPEND, null);
        oversightChannelId = oversightChannel.id;

        // Register agent in the routing registry (tenancyId: default tenant)
        registry.register("test-agent", "278776f9-e1b0-46fb-9032-8bddebdcf9ce", caseId, "test-session-key");

        // Fire CDI event to register the backend for the work channel (simulates startup)
        channelInitEvent.fire(new ChannelInitialisedEvent(workChannelId, workChannelName, false));

        // Gateway mock: return 200 for all invocations
        when(gatewayClient.invokeAgent(any())).thenReturn(Response.ok().build());

        // Override findAllByCorrelationId to scan InMemory store instead of Panache/H2.
        // The InMemory MessageStore stores messages in a HashMap; the real findAllByCorrelationId
        // uses Panache static queries (Message.find(...)) which hit H2 — where InMemory data
        // never lands. This bridge makes fulfill() work in the InMemory test environment.
        doAnswer(invocation -> {
            String correlationId = invocation.getArgument(0);
            return messageStore.scan(MessageQuery.builder().build()).stream()
                    .filter(m -> correlationId.equals(m.correlationId))
                    .sorted(java.util.Comparator.comparingLong(m -> m.id))
                    .toList();
        }).when(messageService).findAllByCorrelationId(any());
    }

    // ── 1. COMMAND invokes OpenClaw with correct body ────────────────────────

    @Test
    void command_invokes_openclaw_with_correct_body() {
        dispatchCommand(workChannelId, "orchestrator", "Analyse the budget.");

        ArgumentCaptor<AgentInvocationRequest> captor = ArgumentCaptor.forClass(AgentInvocationRequest.class);
        verify(gatewayClient).invokeAgent(captor.capture());

        AgentInvocationRequest request = captor.getValue();
        assertThat(request.agentId()).isEqualTo("test-agent");
        assertThat(request.deliver()).isEqualTo("webhook");
        assertThat(request.to()).endsWith("/channel/" + workChannelId);
        // dispatchCommand passes no correlationId → no injection → message is content as-is
        assertThat(request.message()).isEqualTo("Analyse the budget.");
    }

    // ── 2. Delivery webhook archives text as STATUS ──────────────────────────

    @Test
    void delivery_webhook_dispatches_status_to_work_channel() {
        dispatchCommand(workChannelId, "orchestrator", "Do the thing.");

        given()
            .contentType(JSON)
            .body("""
                {"agentId":"test-agent","output":"done"}
                """)
        .when()
            .post("/openclaw/delivery/channel/" + workChannelId)
        .then()
            .statusCode(200);

        List<Message> messages = messageService.pollAfter(workChannelId, 0L, 20);
        List<Message> statusMessages = messages.stream()
                .filter(m -> m.messageType == MessageType.STATUS)
                .toList();
        assertThat(statusMessages).isNotEmpty();
        assertThat(statusMessages.get(0).sender).isEqualTo("test-agent");
        assertThat(statusMessages.get(0).content).isEqualTo("done");
    }

    // ── 3. COMMAND with correlationId injects commitment context ─────────────

    @Test
    void command_with_correlationId_injects_commitment_context() {
        // Call post() directly — the injection happens inside post(), not in the
        // dispatch → fanOut path (which would trigger commitmentService.open() and
        // is separately verified by test 1 without correlationId).
        UUID commitmentId = UUID.randomUUID();
        ChannelRef ref = new ChannelRef(workChannelId, workChannelName);
        OutboundMessage msg = new OutboundMessage(UUID.randomUUID(), "engine",
                MessageType.COMMAND, "Analyse the budget.", commitmentId, null, ActorType.HUMAN);

        channelBackend.post(ref, msg);

        ArgumentCaptor<AgentInvocationRequest> captor = ArgumentCaptor.forClass(AgentInvocationRequest.class);
        verify(gatewayClient).invokeAgent(captor.capture());

        AgentInvocationRequest request = captor.getValue();
        assertThat(request.message()).startsWith("Analyse the budget.");
        assertThat(request.message()).contains(commitmentId.toString());
        assertThat(request.message()).contains("test-agent");
        assertThat(request.message()).contains("casehub_done");
    }

    // ── 4. Delivery to unknown channel returns 200 (fail-open, openclaw#29) ────

    @Test
    void deliver_unknown_channel_returns_200() {
        // Unknown channelId: CrossTenantChannelStore returns empty → tenancyId=null
        // evaluate() with null tenancyId logs a warning and skips dispatch.
        // Always 200 — OpenClaw must not retry (openclaw-delivery-always-200 protocol).
        given()
            .contentType(JSON)
            .body("""
                {"agentId":"test-agent","output":"result"}
                """)
        .when()
            .post("/openclaw/delivery/channel/" + UUID.randomUUID())
        .then()
            .statusCode(200);
    }

    // ── 5. Delivery with invalid UUID returns 400 ────────────────────────────

    @Test
    void deliver_invalid_uuid_returns_400() {
        given()
            .contentType(JSON)
            .body("""
                {"agentId":"test-agent","output":"result"}
                """)
        .when()
            .post("/openclaw/delivery/channel/not-a-uuid")
        .then()
            .statusCode(400);
    }

    // ── 6. Oversight delivery for unknown gateId returns 200 (fail-open) ─────

    @Test
    void oversight_delivery_unknown_gateId_returns_200() {
        given()
            .contentType(JSON)
            .body("""
                {"agentId":"test-agent","output":"approved"}
                """)
        .when()
            .post("/openclaw/delivery/oversight/" + UUID.randomUUID())
        .then()
            .statusCode(200);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Dispatches a COMMAND without a correlationId — no injection occurs. */
    private void dispatchCommand(UUID channelId, String sender, String content) {
        messageService.dispatch(MessageDispatch.builder()
                .channelId(channelId)
                .sender(sender)
                .type(MessageType.COMMAND)
                .content(content)
                .actorType(ActorType.HUMAN)
                .build());
    }
}
