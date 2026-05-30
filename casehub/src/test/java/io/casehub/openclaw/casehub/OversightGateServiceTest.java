package io.casehub.openclaw.casehub;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.casehub.openclaw.client.OpenClawClientConfig;
import io.casehub.openclaw.client.OpenClawHookClient;
import io.casehub.platform.api.identity.ActorType;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OversightGateServiceTest {

    ChannelService channelService;
    MessageService messageService;
    CommitmentStore commitmentStore;
    OpenClawHookClient hookClient;
    OpenClawClientConfig clientConfig;
    OpenClawCasehubConfig casehubConfig;
    SpeechActClassifier speechActClassifier;
    ActionRiskClassifier actionRiskClassifier;
    OversightGateService service;

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
        hookClient = mock(OpenClawHookClient.class);
        clientConfig = mock(OpenClawClientConfig.class);
        casehubConfig = mock(OpenClawCasehubConfig.class);
        speechActClassifier = mock(SpeechActClassifier.class);
        actionRiskClassifier = mock(ActionRiskClassifier.class);

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

        when(speechActClassifier.classify(any())).thenReturn(MessageType.DONE);
        when(actionRiskClassifier.classify(any())).thenReturn(new RiskDecision.Autonomous());

        // Default dispatch result for the COMMAND sent to oversight channel
        when(messageService.dispatch(any())).thenReturn(dispatchResult(42L));

        OpenClawClientConfig.Delivery delivery = mock(OpenClawClientConfig.Delivery.class);
        when(delivery.baseUrl()).thenReturn("http://casehub");
        when(clientConfig.delivery()).thenReturn(delivery);

        OpenClawClientConfig.Agent agent = mock(OpenClawClientConfig.Agent.class);
        when(agent.defaultModel()).thenReturn("claude-opus-4-5");
        when(agent.defaultTimeoutSeconds()).thenReturn(120);
        when(clientConfig.agent()).thenReturn(agent);

        OpenClawCasehubConfig.Oversight oversight = mock(OpenClawCasehubConfig.Oversight.class);
        when(oversight.agentId()).thenReturn(Optional.empty());
        when(casehubConfig.oversight()).thenReturn(oversight);

        service = new OversightGateService(channelService, messageService, commitmentStore,
                hookClient, clientConfig, casehubConfig, speechActClassifier, actionRiskClassifier);
    }

    // ── evaluate() — autonomous path ──────────────────────────────────────────

    @Test
    void evaluate_autonomous_dispatchesStatusToWorkChannel() {
        service.evaluate(workChannelId, "finance-agent", "Analysis complete.");

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());
        MessageDispatch dispatched = captor.getValue();
        assertThat(dispatched.channelId()).isEqualTo(workChannelId);
        assertThat(dispatched.type()).isEqualTo(MessageType.STATUS);
        assertThat(dispatched.sender()).isEqualTo("finance-agent");
        assertThat(dispatched.content()).isEqualTo("Analysis complete.");
        assertThat(dispatched.actorType()).isEqualTo(ActorType.AGENT);
    }

    @Test
    void evaluate_autonomous_doesNotInvokeOpenClaw() {
        service.evaluate(workChannelId, "finance-agent", "done");
        verify(hookClient, never()).invoke(anyString(), anyString(), anyString(), anyInt(), anyString());
    }

    // ── evaluate() — gate required path ───────────────────────────────────────

    @Test
    void evaluate_gateRequired_postsCommandToOversightWithCorrelationId() {
        when(actionRiskClassifier.classify(any()))
                .thenReturn(new RiskDecision.GateRequired("spending limit exceeded", false));

        service.evaluate(workChannelId, "finance-agent", "Cancel Netflix subscription.");

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());
        MessageDispatch dispatched = captor.getValue();
        assertThat(dispatched.channelId()).isEqualTo(oversightChannelId);
        assertThat(dispatched.type()).isEqualTo(MessageType.COMMAND);
        assertThat(dispatched.sender()).isEqualTo("finance-agent");
        assertThat(dispatched.correlationId()).isNotNull();
        assertThat(dispatched.content()).contains("Cancel Netflix subscription.");
    }

    @Test
    void evaluate_gateRequired_invokesOpenClawWithOversightDeliveryUrl() {
        when(actionRiskClassifier.classify(any()))
                .thenReturn(new RiskDecision.GateRequired("irreversible action", true));

        service.evaluate(workChannelId, "finance-agent", "Book non-refundable flight.");

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(hookClient).invoke(anyString(), anyString(), anyString(), anyInt(), urlCaptor.capture());
        assertThat(urlCaptor.getValue()).startsWith("http://casehub/openclaw/delivery/oversight/");
    }

    @Test
    void evaluate_gateRequired_oversightAgentIdDefaultsToWorkAgent() {
        when(actionRiskClassifier.classify(any()))
                .thenReturn(new RiskDecision.GateRequired("risk", true));

        service.evaluate(workChannelId, "finance-agent", "Do thing.");

        ArgumentCaptor<String> agentCaptor = ArgumentCaptor.forClass(String.class);
        verify(hookClient).invoke(agentCaptor.capture(), anyString(), anyString(), anyInt(), anyString());
        assertThat(agentCaptor.getValue()).isEqualTo("finance-agent");
    }

    @Test
    void evaluate_oversightChannelAbsent_failsOpen() {
        when(channelService.findByName("case-" + caseId + "/oversight")).thenReturn(Optional.empty());
        when(actionRiskClassifier.classify(any()))
                .thenReturn(new RiskDecision.GateRequired("risk", false));

        assertThatCode(() -> service.evaluate(workChannelId, "agent", "action")).doesNotThrowAnyException();
        // Only the COMMAND dispatch was attempted (which would fail to find oversight channel)
        verify(hookClient, never()).invoke(anyString(), anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    void evaluate_classifierThrows_failsOpen() {
        when(actionRiskClassifier.classify(any())).thenThrow(new RuntimeException("classifier error"));

        assertThatCode(() -> service.evaluate(workChannelId, "agent", "action")).doesNotThrowAnyException();
        verify(messageService, never()).dispatch(any());
    }

    // ── fulfill() ─────────────────────────────────────────────────────────────

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

    /**
     * Helper: opens a gate via evaluate() and stubs the durable COMMAND lookup so
     * that fulfill() can resolve the inReplyTo value. Returns the gateId extracted
     * from the dispatched COMMAND.
     */
    private UUID openGateAndCaptureGateId() {
        when(actionRiskClassifier.classify(any()))
                .thenReturn(new RiskDecision.GateRequired("risk", true));

        service.evaluate(workChannelId, "finance-agent", "Do risky thing.");

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());
        String correlationId = captor.getValue().correlationId();
        UUID gateId = UUID.fromString(correlationId);

        // Stub the durable lookup that fulfill() uses to resolve inReplyTo
        when(messageService.findAllByCorrelationId(gateId.toString()))
                .thenReturn(List.of(commandMessage(gateId, 42L)));

        return gateId;
    }

    @Test
    void fulfill_approved_dispatchesResponseToOversightAndStatusToWork() {
        UUID gateId = openGateAndCaptureGateId();
        when(commitmentStore.findByCorrelationId(gateId.toString()))
                .thenReturn(Optional.of(commitment(gateId)));

        // Reset mock to capture only fulfill dispatches
        org.mockito.Mockito.clearInvocations(messageService);
        when(messageService.dispatch(any())).thenReturn(dispatchResult(100L));

        service.fulfill(gateId, "approved");

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService, times(2)).dispatch(captor.capture());

        MessageDispatch oversight = captor.getAllValues().get(0);
        assertThat(oversight.channelId()).isEqualTo(oversightChannelId);
        assertThat(oversight.type()).isEqualTo(MessageType.RESPONSE);
        assertThat(oversight.correlationId()).isEqualTo(gateId.toString());
        assertThat(oversight.inReplyTo()).isEqualTo(42L);
        assertThat(oversight.sender()).isEqualTo("openclaw-gate");

        MessageDispatch work = captor.getAllValues().get(1);
        assertThat(work.channelId()).isEqualTo(workChannelId);
        assertThat(work.type()).isEqualTo(MessageType.STATUS);
        assertThat(work.sender()).isEqualTo("openclaw-gate");
    }

    @Test
    void fulfill_rejected_dispatchesDeclineToOversightAndStatusToWork() {
        UUID gateId = openGateAndCaptureGateId();
        when(commitmentStore.findByCorrelationId(gateId.toString()))
                .thenReturn(Optional.of(commitment(gateId)));

        org.mockito.Mockito.clearInvocations(messageService);
        when(messageService.dispatch(any())).thenReturn(dispatchResult(101L));

        service.fulfill(gateId, "rejected");

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService, times(2)).dispatch(captor.capture());

        assertThat(captor.getAllValues().get(0).type()).isEqualTo(MessageType.DECLINE);
        assertThat(captor.getAllValues().get(0).correlationId()).isEqualTo(gateId.toString());
        assertThat(captor.getAllValues().get(0).inReplyTo()).isEqualTo(42L);
        assertThat(captor.getAllValues().get(1).type()).isEqualTo(MessageType.STATUS);
        assertThat(captor.getAllValues().get(1).channelId()).isEqualTo(workChannelId);
    }

    @Test
    void fulfill_rawOutputNull_treatsAsRejected() {
        UUID gateId = openGateAndCaptureGateId();
        when(commitmentStore.findByCorrelationId(gateId.toString()))
                .thenReturn(Optional.of(commitment(gateId)));

        org.mockito.Mockito.clearInvocations(messageService);
        when(messageService.dispatch(any())).thenReturn(dispatchResult(102L));

        service.fulfill(gateId, null);

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService, times(2)).dispatch(captor.capture());
        assertThat(captor.getAllValues().get(0).type()).isEqualTo(MessageType.DECLINE);
    }

    @Test
    void fulfill_rawOutputBlank_treatsAsRejected() {
        UUID gateId = openGateAndCaptureGateId();
        when(commitmentStore.findByCorrelationId(gateId.toString()))
                .thenReturn(Optional.of(commitment(gateId)));

        org.mockito.Mockito.clearInvocations(messageService);
        when(messageService.dispatch(any())).thenReturn(dispatchResult(103L));

        service.fulfill(gateId, "   ");

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService, times(2)).dispatch(captor.capture());
        assertThat(captor.getAllValues().get(0).type()).isEqualTo(MessageType.DECLINE);
    }

    @Test
    void fulfill_approvedWithTrailingText_isApproved() {
        UUID gateId = openGateAndCaptureGateId();
        when(commitmentStore.findByCorrelationId(gateId.toString()))
                .thenReturn(Optional.of(commitment(gateId)));

        org.mockito.Mockito.clearInvocations(messageService);
        when(messageService.dispatch(any())).thenReturn(dispatchResult(104L));

        service.fulfill(gateId, "approved, please go ahead");

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService, times(2)).dispatch(captor.capture());
        assertThat(captor.getAllValues().get(0).type()).isEqualTo(MessageType.RESPONSE);
    }

    @Test
    void fulfill_notApprovedPrefix_isRejected() {
        UUID gateId = openGateAndCaptureGateId();
        when(commitmentStore.findByCorrelationId(gateId.toString()))
                .thenReturn(Optional.of(commitment(gateId)));

        org.mockito.Mockito.clearInvocations(messageService);
        when(messageService.dispatch(any())).thenReturn(dispatchResult(105L));

        service.fulfill(gateId, "not approved");

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService, times(2)).dispatch(captor.capture());
        assertThat(captor.getAllValues().get(0).type()).isEqualTo(MessageType.DECLINE);
    }

    @Test
    void fulfill_noCommandMessageFound_failsOpen() {
        // fulfill() called for a gateId with no COMMAND message in the durable store
        UUID unknownGateId = UUID.randomUUID();
        when(messageService.findAllByCorrelationId(unknownGateId.toString()))
                .thenReturn(List.of());

        assertThatCode(() -> service.fulfill(unknownGateId, "approved")).doesNotThrowAnyException();
        verify(messageService, never()).dispatch(any());
    }

    @Test
    void fulfill_commandFoundButNoCommitment_failsOpen() {
        // COMMAND message exists in durable store but the commitment was already resolved or is missing
        UUID gateId = UUID.randomUUID();
        when(messageService.findAllByCorrelationId(gateId.toString()))
                .thenReturn(List.of(commandMessage(gateId, 42L)));
        when(commitmentStore.findByCorrelationId(gateId.toString())).thenReturn(Optional.empty());

        assertThatCode(() -> service.fulfill(gateId, "approved")).doesNotThrowAnyException();
        verify(messageService, never()).dispatch(any());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private DispatchResult dispatchResult(Long messageId) {
        return new DispatchResult(messageId, oversightChannelId, "agent",
                MessageType.COMMAND, null, null, null, null, null, null, null, 0);
    }
}
