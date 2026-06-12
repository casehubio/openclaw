package io.casehub.openclaw.casehub;

import java.io.StringReader;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

import jakarta.enterprise.inject.Instance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.casehub.api.spi.ActionRiskClassifier;
import io.casehub.api.spi.RiskDecision;
import io.casehub.openclaw.casehub.GateDecision;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.message.CommitmentState;
import io.casehub.qhorus.api.message.DispatchResult;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.message.Commitment;
import io.casehub.qhorus.runtime.message.Message;
import io.casehub.qhorus.runtime.message.MessageService;
import io.casehub.qhorus.runtime.store.CommitmentStore;
import io.casehub.qhorus.runtime.store.CrossTenantMessageStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OversightGateServiceTest {

    ChannelService channelService;
    MessageService messageService;
    CommitmentStore commitmentStore;
    OversightGateDispatcher gateDispatcher;
    CrossTenantMessageStore crossTenantMessageStore;
    OversightGateService service;

    @SuppressWarnings("unchecked")
    Instance<ActionRiskClassifier> classifiers = mock(Instance.class);
    ActionRiskClassifier mockClassifier = mock(ActionRiskClassifier.class);

    String agentId = "test-agent";
    String commitmentId = UUID.randomUUID().toString();
    long commandMsgId = 7L;

    UUID caseId = UUID.randomUUID();
    UUID workChannelId = UUID.randomUUID();
    UUID oversightChannelId = UUID.randomUUID();
    Channel workChannel;
    Channel oversightChannel;

    @BeforeEach
    void setup() {
        channelService = mock(ChannelService.class);
        messageService = mock(MessageService.class);
        commitmentStore = mock(CommitmentStore.class);
        crossTenantMessageStore = mock(CrossTenantMessageStore.class);
        // default: return empty list (no gate COMMAND found)
        when(crossTenantMessageStore.scan(any())).thenReturn(java.util.List.of());

        workChannel = new Channel();
        workChannel.id = workChannelId;
        workChannel.name = "case-" + caseId + "/work";

        oversightChannel = new Channel();
        oversightChannel.id = oversightChannelId;
        oversightChannel.name = "case-" + caseId + "/oversight";

        when(channelService.findById(workChannelId)).thenReturn(Optional.of(workChannel));
        when(channelService.findById(oversightChannelId)).thenReturn(Optional.of(oversightChannel));
        when(channelService.findByName("case-" + caseId + "/oversight"))
                .thenReturn(Optional.of(oversightChannel));
        when(channelService.findByName("case-" + caseId + "/work"))
                .thenReturn(Optional.of(workChannel));

        gateDispatcher = mock(OversightGateDispatcher.class);
        doNothing().when(gateDispatcher).dispatch(
                anyBoolean(), any(), anyLong(), any(), any(), any(), any());

        when(messageService.dispatch(any())).thenReturn(dispatchResult(42L));

        // Default: no classifiers (isUnsatisfied = true)
        when(classifiers.isUnsatisfied()).thenReturn(true);

        service = new OversightGateService(channelService, messageService, commitmentStore,
                gateDispatcher, classifiers, crossTenantMessageStore);
    }

    // ── evaluate() — archival STATUS ──────────────────────────────────────────

    @Test
    void evaluate_withOutput_archivesAsStatus() {
        service.evaluate(workChannelId, "tenant-A", "finance-agent", "Analysis complete.");

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());
        MessageDispatch dispatched = captor.getValue();
        assertThat(dispatched.type()).isEqualTo(MessageType.STATUS);
        assertThat(dispatched.channelId()).isEqualTo(workChannelId);
        assertThat(dispatched.sender()).isEqualTo("finance-agent");
        assertThat(dispatched.content()).isEqualTo("Analysis complete.");
        assertThat(dispatched.actorType()).isEqualTo(ActorType.AGENT);
        // STATUS without correlationId — does not trigger acknowledge() in Qhorus dispatch
        assertThat(dispatched.inReplyTo()).isNull();
        assertThat(dispatched.correlationId()).isNull();
    }

    @Test
    void evaluate_withNullOutput_noDispatch() {
        service.evaluate(workChannelId, "tenant-A", "finance-agent", null);
        verify(messageService, never()).dispatch(any());
    }

    @Test
    void evaluate_withBlankOutput_noDispatch() {
        service.evaluate(workChannelId, "tenant-A", "finance-agent", "   ");
        verify(messageService, never()).dispatch(any());
    }

    @Test
    void evaluate_dispatchException_failsOpen() {
        when(messageService.dispatch(any())).thenThrow(new RuntimeException("db down"));
        assertThatCode(() -> service.evaluate(workChannelId, "tenant-A", "finance-agent", "output"))
                .doesNotThrowAnyException();
    }

    @Test
    void evaluate_withTenancyId_setsOnDispatch() {
        service.evaluate(workChannelId, "tenant-A", "agent-1", "output content");

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());
        assertThat(captor.getValue().tenancyId()).isEqualTo("tenant-A");
        assertThat(captor.getValue().type()).isEqualTo(MessageType.STATUS);
    }

    @Test
    void evaluate_nullTenancyId_skipsDispatch() {
        service.evaluate(UUID.randomUUID(), null, "agent-1", "output");
        verify(messageService, never()).dispatch(any());
    }

    // ── fulfill() ─────────────────────────────────────────────────────────────

    @Test
    void fulfill_approved_callsGateDispatcherWithApprovedTrue() {
        UUID gateId = setupFulfillStubs(oversightChannelId, "approved", "tenant-A", 42L);

        service.fulfill(gateId, "approved");

        ArgumentCaptor<Boolean> approvedCaptor = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<UUID> oversightCaptor = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<Long> inReplyToCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<UUID> gateIdCaptor = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<String> outputCaptor = ArgumentCaptor.forClass(String.class);
        verify(gateDispatcher).dispatch(
                approvedCaptor.capture(), oversightCaptor.capture(),
                inReplyToCaptor.capture(), gateIdCaptor.capture(), outputCaptor.capture(), any(), any());
        assertThat(approvedCaptor.getValue()).isTrue();
        assertThat(oversightCaptor.getValue()).isEqualTo(oversightChannelId);
        assertThat(inReplyToCaptor.getValue()).isEqualTo(42L);
        assertThat(gateIdCaptor.getValue()).isEqualTo(gateId);
        assertThat(outputCaptor.getValue()).isEqualTo("approved");
    }

    @Test
    void fulfill_rejected_callsGateDispatcherWithApprovedFalse() {
        UUID gateId = setupFulfillStubs(oversightChannelId, "rejected", "tenant-A", 42L);

        service.fulfill(gateId, "rejected");

        ArgumentCaptor<Boolean> approvedCaptor = ArgumentCaptor.forClass(Boolean.class);
        verify(gateDispatcher).dispatch(
                approvedCaptor.capture(), any(), anyLong(), any(), any(), any(), any());
        assertThat(approvedCaptor.getValue()).isFalse();
    }

    @Test
    void fulfill_rawOutputNull_treatsAsRejected() {
        UUID gateId = setupFulfillStubs(oversightChannelId, null, "tenant-A", 42L);

        service.fulfill(gateId, null);

        ArgumentCaptor<Boolean> approvedCaptor = ArgumentCaptor.forClass(Boolean.class);
        verify(gateDispatcher).dispatch(
                approvedCaptor.capture(), any(), anyLong(), any(), any(), any(), any());
        assertThat(approvedCaptor.getValue()).isFalse();
    }

    @Test
    void fulfill_rawOutputBlank_treatsAsRejected() {
        UUID gateId = setupFulfillStubs(oversightChannelId, "   ", "tenant-A", 42L);

        service.fulfill(gateId, "   ");

        ArgumentCaptor<Boolean> approvedCaptor = ArgumentCaptor.forClass(Boolean.class);
        verify(gateDispatcher).dispatch(
                approvedCaptor.capture(), any(), anyLong(), any(), any(), any(), any());
        assertThat(approvedCaptor.getValue()).isFalse();
    }

    @Test
    void fulfill_approvedWithTrailingText_isApproved() {
        UUID gateId = setupFulfillStubs(oversightChannelId, "approved, please go ahead", "tenant-A", 42L);

        service.fulfill(gateId, "approved, please go ahead");

        ArgumentCaptor<Boolean> approvedCaptor = ArgumentCaptor.forClass(Boolean.class);
        verify(gateDispatcher).dispatch(
                approvedCaptor.capture(), any(), anyLong(), any(), any(), any(), any());
        assertThat(approvedCaptor.getValue()).isTrue();
    }

    @Test
    void fulfill_notApprovedPrefix_isRejected() {
        UUID gateId = setupFulfillStubs(oversightChannelId, "not approved", "tenant-A", 42L);

        service.fulfill(gateId, "not approved");

        ArgumentCaptor<Boolean> approvedCaptor = ArgumentCaptor.forClass(Boolean.class);
        verify(gateDispatcher).dispatch(
                approvedCaptor.capture(), any(), anyLong(), any(), any(), any(), any());
        assertThat(approvedCaptor.getValue()).isFalse();
    }

    @Test
    void fulfill_noCommandMessageFound_failsOpen() {
        UUID unknownGateId = UUID.randomUUID();
        // crossTenantMessageStore.scan() already returns empty list by default in setUp

        assertThatCode(() -> service.fulfill(unknownGateId, "approved")).doesNotThrowAnyException();
        verify(messageService, never()).dispatch(any());
        verify(gateDispatcher, never()).dispatch(anyBoolean(), any(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void fulfill_gateCmdNotFound_noDispatch() {
        when(crossTenantMessageStore.scan(any())).thenReturn(java.util.List.of());
        service.fulfill(UUID.randomUUID(), "approved");
        verify(gateDispatcher, never()).dispatch(anyBoolean(), any(), anyLong(), any(), any(), any(), any());
    }

    // ── fulfill() — with gate context ────────────────────────────────────────

    @Test
    void fulfill_approved_withContext_passesGateContextToDispatcher() throws Exception {
        UUID gateId = UUID.randomUUID();
        Message gateCmd = buildGateCommand(oversightChannelId, gateId, workChannelId, commitmentId, "tenant-A");
        when(crossTenantMessageStore.scan(any())).thenReturn(List.of(gateCmd));

        service.fulfill(gateId, "approved");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Optional<GateContext>> contextCaptor =
                (ArgumentCaptor<Optional<GateContext>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(Optional.class);
        verify(gateDispatcher).dispatch(
                eq(true), any(), anyLong(), any(), any(), contextCaptor.capture(), any());
        assertThat(contextCaptor.getValue()).isPresent();
        GateContext ctx = contextCaptor.getValue().get();
        assertThat(ctx.originalCommitmentId()).isEqualTo(commitmentId);
        assertThat(ctx.workChannelId()).isEqualTo(workChannelId);
        assertThat(ctx.commandMessageId()).isEqualTo(commandMsgId);
    }

    @Test
    void fulfill_rejected_withContext_passesGateContextToDispatcher() throws Exception {
        UUID gateId = UUID.randomUUID();
        Message gateCmd = buildGateCommand(oversightChannelId, gateId, workChannelId, commitmentId, "tenant-A");
        when(crossTenantMessageStore.scan(any())).thenReturn(List.of(gateCmd));

        service.fulfill(gateId, "rejected");

        ArgumentCaptor<Boolean> approvedCaptor = ArgumentCaptor.forClass(Boolean.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Optional<GateContext>> contextCaptor =
                (ArgumentCaptor<Optional<GateContext>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(Optional.class);
        verify(gateDispatcher).dispatch(
                approvedCaptor.capture(), any(), anyLong(), any(), any(), contextCaptor.capture(), any());
        assertThat(approvedCaptor.getValue()).isFalse();
        assertThat(contextCaptor.getValue()).isPresent();
    }

    @Test
    void fulfill_malformedGateContent_passesEmptyContextToDispatcher() {
        UUID gateId = UUID.randomUUID();
        Message cmd = new Message();
        cmd.id = 42L;
        cmd.channelId = oversightChannelId;
        cmd.messageType = MessageType.COMMAND;
        cmd.correlationId = gateId.toString();
        cmd.content = "not-properties-format-at-all";
        when(crossTenantMessageStore.scan(any())).thenReturn(List.of(cmd));

        service.fulfill(gateId, "approved");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Optional<GateContext>> contextCaptor =
                (ArgumentCaptor<Optional<GateContext>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(Optional.class);
        verify(gateDispatcher).dispatch(
                anyBoolean(), any(), anyLong(), any(), any(), contextCaptor.capture(), any());
        assertThat(contextCaptor.getValue()).isEmpty();
    }

    @Test
    void fulfill_approved_dispatchesWithTenancyIdFromGateContext() {
        UUID gateId = UUID.randomUUID();
        String tenancyId = "tenant-A";
        Message gateCmd = buildGateCommand(oversightChannelId, gateId, workChannelId, commitmentId, tenancyId);
        when(crossTenantMessageStore.scan(any())).thenReturn(List.of(gateCmd));

        service.fulfill(gateId, "approved");

        verify(gateDispatcher).dispatch(
                eq(true),
                eq(oversightChannelId),
                eq(42L),
                eq(gateId),
                eq("approved"),
                argThat(opt -> opt.isPresent() && tenancyId.equals(opt.get().tenancyId())),
                eq(tenancyId)
        );
    }

    // ── openGate() ────────────────────────────────────────────────────────────

    @Test
    void openGate_noClassifiers_returnsAutonomous() {
        when(classifiers.isUnsatisfied()).thenReturn(true);
        stubOpenGateCommitment();

        GateDecision result = service.openGate(agentId, commitmentId, "analysis done", "tenant-A");

        assertThat(result).isInstanceOf(GateDecision.Autonomous.class);
        verify(messageService, never()).dispatch(any());
    }

    @Test
    void openGate_classifierReturnsAutonomous_returnsAutonomous() {
        stubSingleClassifier(new RiskDecision.Autonomous());
        stubOpenGateCommitment();

        GateDecision result = service.openGate(agentId, commitmentId, "analysis done", "tenant-A");

        assertThat(result).isInstanceOf(GateDecision.Autonomous.class);
        verify(messageService, never()).dispatch(any());
    }

    @Test
    void openGate_classifierReturnsGateRequired_dispatchesCommandToOversightAndReturnsPending() {
        stubSingleClassifier(new RiskDecision.GateRequired("risk: file deletion", true, null, null, null));
        stubOpenGateCommitment();

        GateDecision result = service.openGate(agentId, commitmentId, "deleting old reports", "tenant-A");

        assertThat(result).isInstanceOf(GateDecision.GatePending.class);
        GateDecision.GatePending pending = (GateDecision.GatePending) result;
        assertThat(pending.reason()).isEqualTo("risk: file deletion");
        assertThat(pending.gateId()).isNotNull();

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());
        MessageDispatch cmd = captor.getValue();
        assertThat(cmd.channelId()).isEqualTo(oversightChannelId);
        assertThat(cmd.type()).isEqualTo(MessageType.COMMAND);
        assertThat(cmd.sender()).isEqualTo(OversightGateService.GATE_SENDER);
        assertThat(cmd.correlationId()).isEqualTo(pending.gateId().toString());
        assertThat(cmd.content()).isNotBlank();
    }

    @Test
    void openGate_gateCommandContentIsPropertiesFormatContainingOriginalCommitmentId()
            throws Exception {
        stubSingleClassifier(new RiskDecision.GateRequired("risky", true, null, null, null));
        stubOpenGateCommitment();

        service.openGate(agentId, commitmentId, "outcome", "tenant-A");

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());
        String content = captor.getValue().content();

        Properties props = new Properties();
        props.load(new StringReader(content));
        assertThat(props.getProperty("originalCommitmentId")).isEqualTo(commitmentId);
        assertThat(props.getProperty("workChannelId")).isEqualTo(workChannelId.toString());
        assertThat(props.getProperty("commandMessageId")).isEqualTo(String.valueOf(commandMsgId));
        assertThat(props.getProperty("reason")).isEqualTo("risky");
    }

    @Test
    void openGate_classifierThrows_appliesFailSafeGateRequired() {
        stubSingleClassifier_throws(new RuntimeException("classifier crashed"));
        stubOpenGateCommitment();

        GateDecision result = service.openGate(agentId, commitmentId, "outcome", "tenant-A");

        assertThat(result).isInstanceOf(GateDecision.GatePending.class);
        GateDecision.GatePending pending = (GateDecision.GatePending) result;
        assertThat(pending.reason()).contains("Classifier error");
    }

    @Test
    void openGate_oversightChannelMissing_returnsAutonomous() {
        stubSingleClassifier(new RiskDecision.GateRequired("risky", true, null, null, null));
        stubOpenGateCommitment();
        when(channelService.findByName("case-" + caseId + "/oversight"))
                .thenReturn(Optional.empty());

        GateDecision result = service.openGate(agentId, commitmentId, "outcome", "tenant-A");

        assertThat(result).isInstanceOf(GateDecision.Autonomous.class);
        verify(messageService, never()).dispatch(any());
    }

    @Test
    void openGate_dispatchThrows_failsOpenAndReturnsAutonomous() {
        stubSingleClassifier(new RiskDecision.GateRequired("risky", true, null, null, null));
        stubOpenGateCommitment();
        when(messageService.dispatch(any())).thenThrow(new RuntimeException("channel unavailable"));

        GateDecision result = service.openGate(agentId, commitmentId, "outcome", "tenant-A");

        assertThat(result).isInstanceOf(GateDecision.Autonomous.class);
    }

    @Test
    void openGate_noChannelBackedCommitment_returnsAutonomous() {
        stubSingleClassifier(new RiskDecision.GateRequired("risky", true, null, null, null));
        Commitment c = new Commitment();
        c.id = UUID.randomUUID();
        c.correlationId = commitmentId;
        c.channelId = null;  // self-commit — no channel
        c.state = io.casehub.qhorus.api.message.CommitmentState.OPEN;
        when(commitmentStore.findByCorrelationId(commitmentId)).thenReturn(Optional.of(c));

        GateDecision result = service.openGate(agentId, commitmentId, "outcome", "tenant-A");

        assertThat(result).isInstanceOf(GateDecision.Autonomous.class);
        verify(messageService, never()).dispatch(any());
    }

    @Test
    void openGate_mostRestrictive_picksSmallerCandidateGroupsAsMoreRestrictive() {
        ActionRiskClassifier narrowClassifier = mock(ActionRiskClassifier.class);
        ActionRiskClassifier broadClassifier = mock(ActionRiskClassifier.class);
        when(narrowClassifier.classify(any()))
                .thenReturn(new RiskDecision.GateRequired("narrow", true, List.of("admin"), null, null));
        when(broadClassifier.classify(any()))
                .thenReturn(new RiskDecision.GateRequired("broad", true, List.of("admin", "member"), null, null));
        when(classifiers.isUnsatisfied()).thenReturn(false);
        when(classifiers.iterator()).thenReturn(List.of(narrowClassifier, broadClassifier).iterator());
        stubOpenGateCommitment();

        GateDecision result = service.openGate(agentId, commitmentId, "outcome", "tenant-A");

        assertThat(result).isInstanceOf(GateDecision.GatePending.class);
        assertThat(((GateDecision.GatePending) result).reason()).isEqualTo("narrow");
    }

    @Test
    void openGate_twoClassifiersOneAutonomousOneGateRequired_returnsGatePending() {
        ActionRiskClassifier autonomousClassifier = mock(ActionRiskClassifier.class);
        ActionRiskClassifier gateClassifier = mock(ActionRiskClassifier.class);
        when(autonomousClassifier.classify(any())).thenReturn(new RiskDecision.Autonomous());
        when(gateClassifier.classify(any()))
                .thenReturn(new RiskDecision.GateRequired("risk", true, null, null, null));
        when(classifiers.isUnsatisfied()).thenReturn(false);
        when(classifiers.iterator()).thenReturn(List.of(autonomousClassifier, gateClassifier).iterator());
        stubOpenGateCommitment();

        GateDecision result = service.openGate(agentId, commitmentId, "outcome", "tenant-A");

        assertThat(result).isInstanceOf(GateDecision.GatePending.class);
    }

    @Test
    void openGate_noCommandMessage_returnsAutonomousAndNoGateOpened() {
        stubSingleClassifier(new RiskDecision.GateRequired("risky", true, null, null, null));
        Commitment c = new Commitment();
        c.id = UUID.randomUUID();
        c.correlationId = commitmentId;
        c.channelId = workChannelId;
        c.state = io.casehub.qhorus.api.message.CommitmentState.OPEN;
        when(commitmentStore.findByCorrelationId(commitmentId)).thenReturn(Optional.of(c));
        // No COMMAND message in history
        when(messageService.findAllByCorrelationId(commitmentId)).thenReturn(List.of());

        GateDecision result = service.openGate(agentId, commitmentId, "outcome", "tenant-A");

        assertThat(result).isInstanceOf(GateDecision.Autonomous.class);
        verify(messageService, never()).dispatch(any());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void stubOpenGateCommitment() {
        Commitment c = new Commitment();
        c.id = UUID.randomUUID();
        c.correlationId = commitmentId;
        c.channelId = workChannelId;
        c.obligor = agentId;
        c.state = io.casehub.qhorus.api.message.CommitmentState.OPEN;
        c.expiresAt = java.time.Instant.now().plusSeconds(3600);
        when(commitmentStore.findByCorrelationId(commitmentId)).thenReturn(Optional.of(c));

        Message cmd = new Message();
        cmd.id = commandMsgId;
        cmd.messageType = MessageType.COMMAND;
        cmd.correlationId = commitmentId;
        when(messageService.findAllByCorrelationId(commitmentId)).thenReturn(List.of(cmd));
    }

    private void stubSingleClassifier(RiskDecision decision) {
        when(mockClassifier.classify(any())).thenReturn(decision);
        when(classifiers.isUnsatisfied()).thenReturn(false);
        when(classifiers.iterator()).thenReturn(List.of(mockClassifier).iterator());
    }

    private void stubSingleClassifier_throws(RuntimeException ex) {
        when(mockClassifier.classify(any())).thenThrow(ex);
        when(classifiers.isUnsatisfied()).thenReturn(false);
        when(classifiers.iterator()).thenReturn(List.of(mockClassifier).iterator());
    }

    /**
     * Sets up a gate COMMAND message via crossTenantMessageStore for fulfill() tests.
     * The content is a valid Properties-format string with gate context.
     */
    private UUID setupFulfillStubs(UUID oversightChanId, String rawOutput, String tenancyId, long msgId) {
        UUID gateId = UUID.randomUUID();
        Message gateCmd = buildGateCommand(oversightChanId, gateId, workChannelId, commitmentId, tenancyId);
        gateCmd.id = msgId;
        when(crossTenantMessageStore.scan(any())).thenReturn(List.of(gateCmd));
        return gateId;
    }

    /**
     * Builds a gate COMMAND message with Properties-format content containing gate context.
     */
    private Message buildGateCommand(UUID oversightChanId, UUID gateId, UUID workChanId,
                                      String origCommitmentId, String tenancyId) {
        java.util.Properties props = new java.util.Properties();
        props.setProperty("originalCommitmentId", origCommitmentId);
        props.setProperty("workChannelId", workChanId.toString());
        props.setProperty("commandMessageId", String.valueOf(commandMsgId));
        props.setProperty("reason", "test risk");
        if (tenancyId != null) props.setProperty("tenancyId", tenancyId);
        java.io.StringWriter sw = new java.io.StringWriter();
        try { props.store(sw, null); } catch (Exception e) { throw new RuntimeException(e); }

        Message m = new Message();
        m.id = 42L;
        m.channelId = oversightChanId;
        m.messageType = MessageType.COMMAND;
        m.content = sw.toString();
        m.correlationId = gateId.toString();
        return m;
    }

    private DispatchResult dispatchResult(Long messageId) {
        return new DispatchResult(messageId, oversightChannelId, "agent",
                MessageType.COMMAND, null, null, null, null, null, null, null, 0);
    }
}
