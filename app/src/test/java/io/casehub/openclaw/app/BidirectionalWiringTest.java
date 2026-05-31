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

import io.casehub.openclaw.casehub.ActionRiskClassifier;
import io.casehub.openclaw.casehub.OpenClawAgentRegistry;
import io.casehub.openclaw.casehub.RiskDecision;
import io.casehub.openclaw.client.AgentInvocationRequest;
import io.casehub.openclaw.client.OpenClawGatewayClient;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.gateway.ChannelInitialisedEvent;
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
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end integration test for the bidirectional Qhorus-OpenClaw round trip.
 *
 * <p>Uses real CDI wiring (ChannelService, OversightGateService,
 * OpenClawChannelBackend) with InMemory stores from casehub-qhorus-testing. Only
 * {@link OpenClawGatewayClient} (external HTTP) and {@link ActionRiskClassifier}
 * (risk policy) are mocked.
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
 *   <li>Delivery webhook → OversightGateService.evaluate() → STATUS on work channel (AUTONOMOUS)</li>
 *   <li>Gate path: COMMAND on oversight channel → OpenClaw invoked with oversight URL</li>
 *   <li>Oversight response → OversightGateService.fulfill() → RESPONSE/DECLINE + STATUS</li>
 * </ol>
 */
@QuarkusTest
class BidirectionalWiringTest {

    @InjectMock
    @RestClient
    OpenClawGatewayClient gatewayClient;

    @InjectMock
    ActionRiskClassifier actionRiskClassifier;

    @InjectSpy
    MessageService messageService;

    @Inject
    ChannelService channelService;

    @Inject
    MessageStore messageStore;

    @Inject
    OpenClawAgentRegistry registry;

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

        // Register agent in the routing registry
        registry.register("test-agent", caseId, "test-session-key");

        // Fire CDI event to register the backend for the work channel (simulates startup)
        channelInitEvent.fire(new ChannelInitialisedEvent(workChannelId, workChannelName, false));

        // Default: autonomous — no oversight gate
        when(actionRiskClassifier.classify(any())).thenReturn(new RiskDecision.Autonomous());

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
        // The backend constructs: config.delivery().baseUrl() + "/channel/" + channelId
        // In tests, delivery.baseUrl = "http://localhost:{test-port}" — verify the channel suffix
        assertThat(request.to()).endsWith("/channel/" + workChannelId);
        assertThat(request.message()).isEqualTo("Analyse the budget.");
    }

    // ── 2. Delivery webhook dispatches STATUS to work channel ────────────────

    @Test
    void delivery_webhook_dispatches_status_to_work_channel() {
        // Trigger the COMMAND → OpenClaw invocation
        dispatchCommand(workChannelId, "orchestrator", "Do the thing.");

        // Simulate OpenClaw delivering the result back via webhook
        given()
            .contentType(JSON)
            .body("""
                {"agentId":"test-agent","output":"done"}
                """)
        .when()
            .post("/openclaw/delivery/channel/" + workChannelId)
        .then()
            .statusCode(200);

        // Verify STATUS message appeared on work channel
        List<Message> messages = messageService.pollAfter(workChannelId, 0L, 20);
        List<Message> statusMessages = messages.stream()
                .filter(m -> m.messageType == MessageType.STATUS)
                .toList();
        assertThat(statusMessages).isNotEmpty();
        assertThat(statusMessages.get(0).sender).isEqualTo("test-agent");
        assertThat(statusMessages.get(0).content).isEqualTo("done");
    }

    // ── 3. Gate required posts COMMAND to oversight channel ──────────────────

    @Test
    void gate_required_posts_command_to_oversight_channel() {
        when(actionRiskClassifier.classify(any()))
                .thenReturn(new RiskDecision.GateRequired("spending limit", false));

        // Trigger COMMAND on work channel
        dispatchCommand(workChannelId, "orchestrator", "Cancel subscription.");

        // Simulate OpenClaw delivering the result (triggers evaluate → gate path)
        given()
            .contentType(JSON)
            .body("""
                {"agentId":"test-agent","output":"Cancel Netflix subscription."}
                """)
        .when()
            .post("/openclaw/delivery/channel/" + workChannelId)
        .then()
            .statusCode(200);

        // Verify COMMAND on oversight channel with correlationId (gateId)
        List<Message> oversightMessages = messageService.pollAfter(oversightChannelId, 0L, 20);
        List<Message> commands = oversightMessages.stream()
                .filter(m -> m.messageType == MessageType.COMMAND)
                .toList();
        assertThat(commands).hasSize(1);
        assertThat(commands.get(0).correlationId).isNotNull();
        assertThat(commands.get(0).sender).isEqualTo("test-agent");
    }

    // ── 4. Gate required invokes OpenClaw with oversight URL ─────────────────

    @Test
    void gate_required_invokes_openclaw_with_oversight_url() {
        when(actionRiskClassifier.classify(any()))
                .thenReturn(new RiskDecision.GateRequired("irreversible action", true));

        dispatchCommand(workChannelId, "orchestrator", "Book flight.");

        // Delivery triggers evaluate → gate path → second invoke for oversight
        given()
            .contentType(JSON)
            .body("""
                {"agentId":"test-agent","output":"Non-refundable flight to Tokyo."}
                """)
        .when()
            .post("/openclaw/delivery/channel/" + workChannelId)
        .then()
            .statusCode(200);

        // Verify at least one invoke with oversight delivery URL
        ArgumentCaptor<AgentInvocationRequest> captor = ArgumentCaptor.forClass(AgentInvocationRequest.class);
        verify(gatewayClient, atLeastOnce()).invokeAgent(captor.capture());

        List<AgentInvocationRequest> oversightInvocations = captor.getAllValues().stream()
                .filter(r -> r.to() != null && r.to().contains("/openclaw/delivery/oversight/"))
                .toList();
        assertThat(oversightInvocations).isNotEmpty();
    }

    // ── 5. Oversight approval dispatches RESPONSE and STATUS ─────────────────

    @Test
    void oversight_approval_dispatches_response_and_status() {
        when(actionRiskClassifier.classify(any()))
                .thenReturn(new RiskDecision.GateRequired("spending limit", false));

        dispatchCommand(workChannelId, "orchestrator", "Cancel subscription.");

        // Delivery triggers gate
        given()
            .contentType(JSON)
            .body("""
                {"agentId":"test-agent","output":"Cancel Netflix."}
                """)
        .when()
            .post("/openclaw/delivery/channel/" + workChannelId)
        .then()
            .statusCode(200);

        // Extract gateId from oversight channel COMMAND's correlationId
        List<Message> oversightMessages = messageService.pollAfter(oversightChannelId, 0L, 20);
        Message gateCommand = oversightMessages.stream()
                .filter(m -> m.messageType == MessageType.COMMAND)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected COMMAND on oversight channel"));
        String gateId = gateCommand.correlationId;

        // Simulate human approval via oversight delivery
        given()
            .contentType(JSON)
            .body("""
                {"agentId":"test-agent","output":"approved"}
                """)
        .when()
            .post("/openclaw/delivery/oversight/" + gateId)
        .then()
            .statusCode(200);

        // Verify RESPONSE on oversight channel (correlationId matches gateId)
        List<Message> oversightAfterApproval = messageService.pollAfter(oversightChannelId, 0L, 20);
        List<Message> responses = oversightAfterApproval.stream()
                .filter(m -> m.messageType == MessageType.RESPONSE)
                .toList();
        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).correlationId).isEqualTo(gateId);
        assertThat(responses.get(0).sender).isEqualTo("openclaw-gate");

        // Verify STATUS on work channel (notification of gate approval)
        List<Message> workMessages = messageService.pollAfter(workChannelId, 0L, 20);
        List<Message> workStatuses = workMessages.stream()
                .filter(m -> m.messageType == MessageType.STATUS && "openclaw-gate".equals(m.sender))
                .toList();
        assertThat(workStatuses).isNotEmpty();
        assertThat(workStatuses.get(0).content).contains("approved");
    }

    // ── 6. Oversight rejection dispatches DECLINE ────────────────────────────

    @Test
    void oversight_rejection_dispatches_decline() {
        when(actionRiskClassifier.classify(any()))
                .thenReturn(new RiskDecision.GateRequired("high risk", false));

        dispatchCommand(workChannelId, "orchestrator", "Delete account.");

        given()
            .contentType(JSON)
            .body("""
                {"agentId":"test-agent","output":"Delete user account."}
                """)
        .when()
            .post("/openclaw/delivery/channel/" + workChannelId)
        .then()
            .statusCode(200);

        // Get gateId
        List<Message> oversightMessages = messageService.pollAfter(oversightChannelId, 0L, 20);
        Message gateCommand = oversightMessages.stream()
                .filter(m -> m.messageType == MessageType.COMMAND)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected COMMAND on oversight channel"));
        String gateId = gateCommand.correlationId;

        // Simulate human rejection
        given()
            .contentType(JSON)
            .body("""
                {"agentId":"test-agent","output":"rejected"}
                """)
        .when()
            .post("/openclaw/delivery/oversight/" + gateId)
        .then()
            .statusCode(200);

        // Verify DECLINE on oversight channel
        List<Message> oversightAfter = messageService.pollAfter(oversightChannelId, 0L, 20);
        List<Message> declines = oversightAfter.stream()
                .filter(m -> m.messageType == MessageType.DECLINE)
                .toList();
        assertThat(declines).hasSize(1);
        assertThat(declines.get(0).correlationId).isEqualTo(gateId);
        assertThat(declines.get(0).sender).isEqualTo("openclaw-gate");

        // Verify STATUS on work channel (notification of gate rejection)
        List<Message> workMessages = messageService.pollAfter(workChannelId, 0L, 20);
        List<Message> workStatuses = workMessages.stream()
                .filter(m -> m.messageType == MessageType.STATUS && "openclaw-gate".equals(m.sender))
                .toList();
        assertThat(workStatuses).isNotEmpty();
    }

    // ── 7. Delivery to unknown channel returns 404 ───────────────────────────

    @Test
    void deliver_unknown_channel_returns_404() {
        given()
            .contentType(JSON)
            .body("""
                {"agentId":"test-agent","output":"result"}
                """)
        .when()
            .post("/openclaw/delivery/channel/" + UUID.randomUUID())
        .then()
            .statusCode(404);
    }

    // ── 8. Delivery with invalid UUID returns 400 ────────────────────────────

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

    // ── 9. Oversight delivery for unknown gateId returns 200 (fail-open) ─────

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

    /** Dispatches a COMMAND to the given channel via the real MessageService. */
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
