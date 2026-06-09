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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
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
                anyBoolean(), any(), any(), anyLong(), any(), any(), any());

        when(messageService.dispatch(any())).thenReturn(dispatchResult(42L));

        // Default: no classifiers (isUnsatisfied = true)
        when(classifiers.isUnsatisfied()).thenReturn(true);

        service = new OversightGateService(channelService, messageService, commitmentStore,
                gateDispatcher, classifiers);
    }

    // ── evaluate() — archival STATUS ──────────────────────────────────────────

    @Test
    void evaluate_withOutput_archivesAsStatus() {
        service.evaluate(workChannelId, "finance-agent", "Analysis complete.");

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
        service.evaluate(workChannelId, "finance-agent", null);
        verify(messageService, never()).dispatch(any());
    }

    @Test
    void evaluate_withBlankOutput_noDispatch() {
        service.evaluate(workChannelId, "finance-agent", "   ");
        verify(messageService, never()).dispatch(any());
    }

    @Test
    void evaluate_dispatchException_failsOpen() {
        when(messageService.dispatch(any())).thenThrow(new RuntimeException("db down"));
        assertThatCode(() -> service.evaluate(workChannelId, "finance-agent", "output"))
                .doesNotThrowAnyException();
    }

    // ── fulfill() ─────────────────────────────────────────────────────────────

    @Test
    void fulfill_approved_callsGateDispatcherWithApprovedTrue() {
        UUID gateId = setupGateStubs();
        when(commitmentStore.findByCorrelationId(gateId.toString()))
                .thenReturn(Optional.of(commitment(gateId)));

        service.fulfill(gateId, "approved");

        ArgumentCaptor<Boolean> approvedCaptor = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<UUID> oversightCaptor = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<UUID> workCaptor = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<Long> inReplyToCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<UUID> gateIdCaptor = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<String> outputCaptor = ArgumentCaptor.forClass(String.class);
        verify(gateDispatcher).dispatch(
                approvedCaptor.capture(), oversightCaptor.capture(), workCaptor.capture(),
                inReplyToCaptor.capture(), gateIdCaptor.capture(), outputCaptor.capture(), any());
        assertThat(approvedCaptor.getValue()).isTrue();
        assertThat(oversightCaptor.getValue()).isEqualTo(oversightChannelId);
        assertThat(workCaptor.getValue()).isEqualTo(workChannelId);
        assertThat(inReplyToCaptor.getValue()).isEqualTo(42L);
        assertThat(gateIdCaptor.getValue()).isEqualTo(gateId);
        assertThat(outputCaptor.getValue()).isEqualTo("approved");
    }

    @Test
    void fulfill_rejected_callsGateDispatcherWithApprovedFalse() {
        UUID gateId = setupGateStubs();
        when(commitmentStore.findByCorrelationId(gateId.toString()))
                .thenReturn(Optional.of(commitment(gateId)));

        service.fulfill(gateId, "rejected");

        ArgumentCaptor<Boolean> approvedCaptor = ArgumentCaptor.forClass(Boolean.class);
        verify(gateDispatcher).dispatch(
                approvedCaptor.capture(), any(), any(), anyLong(), any(), any(), any());
        assertThat(approvedCaptor.getValue()).isFalse();
    }

    @Test
    void fulfill_rawOutputNull_treatsAsRejected() {
        UUID gateId = setupGateStubs();
        when(commitmentStore.findByCorrelationId(gateId.toString()))
                .thenReturn(Optional.of(commitment(gateId)));

        service.fulfill(gateId, null);

        ArgumentCaptor<Boolean> approvedCaptor = ArgumentCaptor.forClass(Boolean.class);
        verify(gateDispatcher).dispatch(
                approvedCaptor.capture(), any(), any(), anyLong(), any(), any(), any());
        assertThat(approvedCaptor.getValue()).isFalse();
    }

    @Test
    void fulfill_rawOutputBlank_treatsAsRejected() {
        UUID gateId = setupGateStubs();
        when(commitmentStore.findByCorrelationId(gateId.toString()))
                .thenReturn(Optional.of(commitment(gateId)));

        service.fulfill(gateId, "   ");

        ArgumentCaptor<Boolean> approvedCaptor = ArgumentCaptor.forClass(Boolean.class);
        verify(gateDispatcher).dispatch(
                approvedCaptor.capture(), any(), any(), anyLong(), any(), any(), any());
        assertThat(approvedCaptor.getValue()).isFalse();
    }

    @Test
    void fulfill_approvedWithTrailingText_isApproved() {
        UUID gateId = setupGateStubs();
        when(commitmentStore.findByCorrelationId(gateId.toString()))
                .thenReturn(Optional.of(commitment(gateId)));

        service.fulfill(gateId, "approved, please go ahead");

        ArgumentCaptor<Boolean> approvedCaptor = ArgumentCaptor.forClass(Boolean.class);
        verify(gateDispatcher).dispatch(
                approvedCaptor.capture(), any(), any(), anyLong(), any(), any(), any());
        assertThat(approvedCaptor.getValue()).isTrue();
    }

    @Test
    void fulfill_notApprovedPrefix_isRejected() {
        UUID gateId = setupGateStubs();
        when(commitmentStore.findByCorrelationId(gateId.toString()))
                .thenReturn(Optional.of(commitment(gateId)));

        service.fulfill(gateId, "not approved");

        ArgumentCaptor<Boolean> approvedCaptor = ArgumentCaptor.forClass(Boolean.class);
        verify(gateDispatcher).dispatch(
                approvedCaptor.capture(), any(), any(), anyLong(), any(), any(), any());
        assertThat(approvedCaptor.getValue()).isFalse();
    }

    @Test
    void fulfill_noCommandMessageFound_failsOpen() {
        UUID unknownGateId = UUID.randomUUID();
        when(messageService.findAllByCorrelationId(unknownGateId.toString()))
                .thenReturn(List.of());

        assertThatCode(() -> service.fulfill(unknownGateId, "approved")).doesNotThrowAnyException();
        verify(messageService, never()).dispatch(any());
    }

    @Test
    void fulfill_commandFoundButNoCommitment_failsOpen() {
        UUID gateId = UUID.randomUUID();
        when(messageService.findAllByCorrelationId(gateId.toString()))
                .thenReturn(List.of(commandMessage(gateId, 42L)));
        when(commitmentStore.findByCorrelationId(gateId.toString())).thenReturn(Optional.empty());

        assertThatCode(() -> service.fulfill(gateId, "approved")).doesNotThrowAnyException();
        verify(messageService, never()).dispatch(any());
    }

    // ── openGate() ────────────────────────────────────────────────────────────

    @Test
    void openGate_noClassifiers_returnsAutonomous() {
        when(classifiers.isUnsatisfied()).thenReturn(true);
        stubOpenGateCommitment();

        GateDecision result = service.openGate(agentId, commitmentId, "analysis done");

        assertThat(result).isInstanceOf(GateDecision.Autonomous.class);
        verify(messageService, never()).dispatch(any());
    }

    @Test
    void openGate_classifierReturnsAutonomous_returnsAutonomous() {
        stubSingleClassifier(new RiskDecision.Autonomous());
        stubOpenGateCommitment();

        GateDecision result = service.openGate(agentId, commitmentId, "analysis done");

        assertThat(result).isInstanceOf(GateDecision.Autonomous.class);
        verify(messageService, never()).dispatch(any());
    }

    @Test
    void openGate_classifierReturnsGateRequired_dispatchesCommandToOversightAndReturnsPending() {
        stubSingleClassifier(new RiskDecision.GateRequired("risk: file deletion", true, null, null, null));
        stubOpenGateCommitment();

        GateDecision result = service.openGate(agentId, commitmentId, "deleting old reports");

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

        service.openGate(agentId, commitmentId, "outcome");

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

        GateDecision result = service.openGate(agentId, commitmentId, "outcome");

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

        GateDecision result = service.openGate(agentId, commitmentId, "outcome");

        assertThat(result).isInstanceOf(GateDecision.Autonomous.class);
        verify(messageService, never()).dispatch(any());
    }

    @Test
    void openGate_dispatchThrows_failsOpenAndReturnsAutonomous() {
        stubSingleClassifier(new RiskDecision.GateRequired("risky", true, null, null, null));
        stubOpenGateCommitment();
        when(messageService.dispatch(any())).thenThrow(new RuntimeException("channel unavailable"));

        GateDecision result = service.openGate(agentId, commitmentId, "outcome");

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

        GateDecision result = service.openGate(agentId, commitmentId, "outcome");

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

        GateDecision result = service.openGate(agentId, commitmentId, "outcome");

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

        GateDecision result = service.openGate(agentId, commitmentId, "outcome");

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

        GateDecision result = service.openGate(agentId, commitmentId, "outcome");

        assertThat(result).isInstanceOf(GateDecision.Autonomous.class);
        verify(messageService, never()).dispatch(any());
    }

    // ── fulfill() — with gate context (Phase 2 behaviour) ────────────────────

    @Test
    void fulfill_approved_withContext_passesGateContextToDispatcher() throws Exception {
        UUID gateId = setupGateStubsWithContext();
        when(commitmentStore.findByCorrelationId(gateId.toString()))
                .thenReturn(Optional.of(commitment(gateId)));

        service.fulfill(gateId, "approved");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Optional<GateContext>> contextCaptor =
                (ArgumentCaptor<Optional<GateContext>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(Optional.class);
        verify(gateDispatcher).dispatch(
                eq(true), any(), any(), anyLong(), any(), any(), contextCaptor.capture());
        assertThat(contextCaptor.getValue()).isPresent();
        GateContext ctx = contextCaptor.getValue().get();
        assertThat(ctx.originalCommitmentId()).isEqualTo(commitmentId);
        assertThat(ctx.workChannelId()).isEqualTo(workChannelId);
        assertThat(ctx.commandMessageId()).isEqualTo(commandMsgId);
    }

    @Test
    void fulfill_rejected_withContext_passesGateContextToDispatcher() throws Exception {
        UUID gateId = setupGateStubsWithContext();
        when(commitmentStore.findByCorrelationId(gateId.toString()))
                .thenReturn(Optional.of(commitment(gateId)));

        service.fulfill(gateId, "rejected");

        ArgumentCaptor<Boolean> approvedCaptor = ArgumentCaptor.forClass(Boolean.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Optional<GateContext>> contextCaptor =
                (ArgumentCaptor<Optional<GateContext>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(Optional.class);
        verify(gateDispatcher).dispatch(
                approvedCaptor.capture(), any(), any(), anyLong(), any(), any(), contextCaptor.capture());
        assertThat(approvedCaptor.getValue()).isFalse();
        assertThat(contextCaptor.getValue()).isPresent();
    }

    @Test
    void fulfill_malformedGateContent_passesEmptyContextToDispatcher() {
        UUID gateId = UUID.randomUUID();
        Message cmd = new Message();
        cmd.id = 42L;
        cmd.messageType = MessageType.COMMAND;
        cmd.correlationId = gateId.toString();
        cmd.content = "not-properties-format-at-all";
        when(messageService.findAllByCorrelationId(gateId.toString()))
                .thenReturn(List.of(cmd));
        when(commitmentStore.findByCorrelationId(gateId.toString()))
                .thenReturn(Optional.of(commitment(gateId)));

        service.fulfill(gateId, "approved");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Optional<GateContext>> contextCaptor =
                (ArgumentCaptor<Optional<GateContext>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(Optional.class);
        verify(gateDispatcher).dispatch(
                anyBoolean(), any(), any(), anyLong(), any(), any(), contextCaptor.capture());
        assertThat(contextCaptor.getValue()).isEmpty();
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
     * Stubs a gate COMMAND message with valid Properties-format content so that
     * parseGateContent() returns a populated Optional<GateContext>.
     * Used by the fulfill()-with-context tests.
     */
    private UUID setupGateStubsWithContext() throws Exception {
        UUID gateId = UUID.randomUUID();
        java.util.Properties props = new java.util.Properties();
        props.setProperty("originalCommitmentId", commitmentId);
        props.setProperty("workChannelId", workChannelId.toString());
        props.setProperty("commandMessageId", String.valueOf(commandMsgId));
        props.setProperty("reason", "test reason");
        java.io.StringWriter sw = new java.io.StringWriter();
        props.store(sw, null);
        String content = sw.toString();

        Message cmd = new Message();
        cmd.id = 42L;
        cmd.messageType = MessageType.COMMAND;
        cmd.correlationId = gateId.toString();
        cmd.content = content;
        when(messageService.findAllByCorrelationId(gateId.toString()))
                .thenReturn(List.of(cmd));
        return gateId;
    }

    /**
     * Stubs the message lookup fulfill() requires without calling service.evaluate().
     * Used by all fulfill() tests that need a gateId in the durable message store.
     */
    private UUID setupGateStubs() {
        UUID gateId = UUID.randomUUID();
        when(messageService.findAllByCorrelationId(gateId.toString()))
                .thenReturn(List.of(commandMessage(gateId, 42L)));
        return gateId;
    }

    private Commitment commitment(UUID gateId) {
        Commitment c = new Commitment();
        c.channelId = oversightChannelId;
        c.correlationId = gateId.toString();
        return c;
    }

    private Message commandMessage(UUID gateId, long messageId) {
        Message m = new Message();
        m.id = messageId;
        m.messageType = MessageType.COMMAND;
        m.correlationId = gateId.toString();
        return m;
    }

    private DispatchResult dispatchResult(Long messageId) {
        return new DispatchResult(messageId, oversightChannelId, "agent",
                MessageType.COMMAND, null, null, null, null, null, null, null, 0);
    }
}
