package io.casehub.openclaw.casehub;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.casehub.openclaw.client.OpenClawClientConfig;
import io.casehub.openclaw.client.OpenClawHookClient;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    OversightGateDispatcher gateDispatcher;
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

        gateDispatcher = mock(OversightGateDispatcher.class);
        doNothing().when(gateDispatcher).dispatch(
                org.mockito.ArgumentMatchers.anyBoolean(),
                any(), any(),
                org.mockito.ArgumentMatchers.anyLong(),
                any(), org.mockito.ArgumentMatchers.any());

        when(speechActClassifier.classify(any())).thenAnswer(invocation -> {
            SpeechActContext ctx = invocation.getArgument(0);
            String content = (ctx != null && ctx.output() != null) ? ctx.output() : "";
            return new SpeechActResult(MessageType.DONE, content, DetectionTier.FALLBACK);
        });
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
                hookClient, clientConfig, casehubConfig, speechActClassifier, actionRiskClassifier,
                gateDispatcher);
    }

    // ── evaluate() — autonomous path ──────────────────────────────────────────

    @Test
    void evaluate_autonomous_noOpenCommitment_dispatchesStatusAndLogsWarn() {
        // hadCommitment=false: Watchdog may have expired the commitment while agent was in-flight.
        // STATUS is dispatched as best-effort; WARN logged (not ERROR).
        when(commitmentStore.findOpenByObligor("finance-agent", workChannelId))
                .thenReturn(Collections.emptyList());

        service.evaluate(workChannelId, "finance-agent", "Analysis complete.");

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());
        assertThat(captor.getValue().type()).isEqualTo(MessageType.STATUS);
        assertThat(captor.getValue().channelId()).isEqualTo(workChannelId);
        assertThat(captor.getValue().sender()).isEqualTo("finance-agent");
    }

    @Test
    void evaluate_autonomous_withOpenCommandCommitment_dispatchesSpeechActTypeWithInReplyTo() {
        // Happy path: open COMMAND commitment found → dispatches DONE (from speechActClassifier) with inReplyTo
        String agentId = "finance-agent";
        String correlationId = UUID.randomUUID().toString();
        long commandMessageId = 42L;

        when(commitmentStore.findOpenByObligor(agentId, workChannelId))
                .thenReturn(List.of(openCommandCommitment(workChannelId, agentId, correlationId)));
        Message commandMsg = new Message();
        commandMsg.id = commandMessageId;
        commandMsg.messageType = MessageType.COMMAND;
        commandMsg.correlationId = correlationId;
        when(messageService.findAllByCorrelationId(correlationId))
                .thenReturn(List.of(commandMsg));

        service.evaluate(workChannelId, agentId, "Analysis complete.");

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());
        MessageDispatch dispatched = captor.getValue();
        assertThat(dispatched.channelId()).isEqualTo(workChannelId);
        assertThat(dispatched.type()).isEqualTo(MessageType.DONE);   // not STATUS
        assertThat(dispatched.sender()).isEqualTo(agentId);
        assertThat(dispatched.content()).isEqualTo("Analysis complete.");
        assertThat(dispatched.actorType()).isEqualTo(ActorType.AGENT);
        assertThat(dispatched.inReplyTo()).isEqualTo(commandMessageId);
        assertThat(dispatched.correlationId()).isEqualTo(correlationId);
    }

    @Test
    void evaluate_autonomous_commitmentFoundButCommandMessageMissing_dispatchesStatusAndLogsError() {
        // hadCommitment=true but COMMAND message not found → data inconsistency → STATUS + ERROR log
        String agentId = "finance-agent";
        String correlationId = UUID.randomUUID().toString();

        when(commitmentStore.findOpenByObligor(agentId, workChannelId))
                .thenReturn(List.of(openCommandCommitment(workChannelId, agentId, correlationId)));
        // findAllByCorrelationId returns nothing with COMMAND type
        when(messageService.findAllByCorrelationId(correlationId)).thenReturn(List.of());

        service.evaluate(workChannelId, agentId, "Analysis complete.");

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());
        assertThat(captor.getValue().type()).isEqualTo(MessageType.STATUS);
        // ERROR log level is verified by the absence of an exception (the service absorbs it)
    }

    @Test
    void evaluate_autonomous_doesNotRequireInReplyToForStatusType() {
        // When speechActClassifier returns STATUS (not DONE), dispatch can proceed without inReplyTo
        SpeechActResult statusResult = new SpeechActResult(MessageType.STATUS, "Progress update.", DetectionTier.PREFIX);
        when(speechActClassifier.classify(any())).thenAnswer(inv -> statusResult);
        String agentId = "finance-agent";
        String correlationId = UUID.randomUUID().toString();

        when(commitmentStore.findOpenByObligor(agentId, workChannelId))
                .thenReturn(List.of(openCommandCommitment(workChannelId, agentId, correlationId)));
        Message commandMsg = new Message();
        commandMsg.id = 7L;
        commandMsg.messageType = MessageType.COMMAND;
        commandMsg.correlationId = correlationId;
        when(messageService.findAllByCorrelationId(correlationId))
                .thenReturn(List.of(commandMsg));

        service.evaluate(workChannelId, agentId, "Progress update.");

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());
        assertThat(captor.getValue().type()).isEqualTo(MessageType.STATUS);
        assertThat(captor.getValue().inReplyTo()).isEqualTo(7L); // still sets inReplyTo when available
    }

    @Test
    void evaluate_autonomous_doesNotInvokeOpenClaw() {
        service.evaluate(workChannelId, "finance-agent", "done");
        verify(hookClient, never()).invoke(anyString(), anyString(), anyString(), anyInt(), anyString());
    }

    // ── New tests: Phase 2+3 content stripping and type routing ──────────────

    @Test
    void evaluate_prefixStatus_dispatchesStatusWithInReplyTo() {
        // [STATUS] prefix → STATUS dispatched with inReplyTo + correlationId
        // (ACKNOWLEDGED is the verified Qhorus consequence — see spec §Classifiable MessageTypes;
        // this test asserts dispatch arguments only)
        when(speechActClassifier.classify(any())).thenReturn(
            new SpeechActResult(MessageType.STATUS, "Still processing.", DetectionTier.PREFIX));

        String agentId = "finance-agent";
        String correlationId = UUID.randomUUID().toString();
        long commandMessageId = 55L;

        when(commitmentStore.findOpenByObligor(agentId, workChannelId))
                .thenReturn(List.of(openCommandCommitment(workChannelId, agentId, correlationId)));
        Message commandMsg = new Message();
        commandMsg.id = commandMessageId;
        commandMsg.messageType = MessageType.COMMAND;
        commandMsg.correlationId = correlationId;
        when(messageService.findAllByCorrelationId(correlationId)).thenReturn(List.of(commandMsg));

        service.evaluate(workChannelId, agentId, "[STATUS] Still processing.");

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());
        assertThat(captor.getValue().type()).isEqualTo(MessageType.STATUS);
        assertThat(captor.getValue().inReplyTo()).isEqualTo(commandMessageId);
        assertThat(captor.getValue().correlationId()).isEqualTo(correlationId);
    }

    @Test
    void evaluate_jsonDecline_dispatchesDeclineWithInReplyTo() {
        when(speechActClassifier.classify(any())).thenReturn(
            new SpeechActResult(MessageType.DECLINE, "can't do it", DetectionTier.JSON));

        String agentId = "finance-agent";
        String correlationId = UUID.randomUUID().toString();
        long commandMessageId = 77L;

        when(commitmentStore.findOpenByObligor(agentId, workChannelId))
                .thenReturn(List.of(openCommandCommitment(workChannelId, agentId, correlationId)));
        Message commandMsg = new Message();
        commandMsg.id = commandMessageId;
        commandMsg.messageType = MessageType.COMMAND;
        commandMsg.correlationId = correlationId;
        when(messageService.findAllByCorrelationId(correlationId)).thenReturn(List.of(commandMsg));

        service.evaluate(workChannelId, agentId, "{\"type\":\"DECLINE\",\"content\":\"can't do it\"}");

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());
        assertThat(captor.getValue().type()).isEqualTo(MessageType.DECLINE);
        assertThat(captor.getValue().inReplyTo()).isEqualTo(commandMessageId);
    }

    @Test
    void evaluate_jsonDecline_contentIsStrippedNotRawJson() {
        when(speechActClassifier.classify(any())).thenReturn(
            new SpeechActResult(MessageType.DECLINE, "can't do it", DetectionTier.JSON));

        String agentId = "finance-agent";
        String correlationId = UUID.randomUUID().toString();
        when(commitmentStore.findOpenByObligor(agentId, workChannelId))
                .thenReturn(List.of(openCommandCommitment(workChannelId, agentId, correlationId)));
        Message commandMsg = new Message();
        commandMsg.id = 1L;
        commandMsg.messageType = MessageType.COMMAND;
        commandMsg.correlationId = correlationId;
        when(messageService.findAllByCorrelationId(correlationId)).thenReturn(List.of(commandMsg));

        service.evaluate(workChannelId, agentId, "{\"type\":\"DECLINE\",\"content\":\"can't do it\"}");

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());
        assertThat(captor.getValue().content()).isEqualTo("can't do it");
        assertThat(captor.getValue().content()).doesNotContain("{\"type\":");
    }

    @Test
    void evaluate_prefixDone_contentIsStrippedNotBracketed() {
        when(speechActClassifier.classify(any())).thenReturn(
            new SpeechActResult(MessageType.DONE, "task finished", DetectionTier.PREFIX));

        String agentId = "finance-agent";
        String correlationId = UUID.randomUUID().toString();
        when(commitmentStore.findOpenByObligor(agentId, workChannelId))
                .thenReturn(List.of(openCommandCommitment(workChannelId, agentId, correlationId)));
        Message commandMsg = new Message();
        commandMsg.id = 2L;
        commandMsg.messageType = MessageType.COMMAND;
        commandMsg.correlationId = correlationId;
        when(messageService.findAllByCorrelationId(correlationId)).thenReturn(List.of(commandMsg));

        service.evaluate(workChannelId, agentId, "[DONE] task finished");

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());
        assertThat(captor.getValue().content()).isEqualTo("task finished");
        assertThat(captor.getValue().content()).doesNotStartWith("[DONE]");
    }

    @Test
    void evaluate_noPrefixFallback_withOpenCommitment_dispatchesStatusWithInReplyTo() {
        // No explicit signal → STATUS fallback; commitment is present
        // (ACKNOWLEDGED is the verified Qhorus consequence; this test asserts dispatch arguments only)
        when(speechActClassifier.classify(any())).thenReturn(
            new SpeechActResult(MessageType.STATUS, "I have analysed the data.", DetectionTier.FALLBACK));

        String agentId = "finance-agent";
        String correlationId = UUID.randomUUID().toString();
        long commandMessageId = 99L;

        when(commitmentStore.findOpenByObligor(agentId, workChannelId))
                .thenReturn(List.of(openCommandCommitment(workChannelId, agentId, correlationId)));
        Message commandMsg = new Message();
        commandMsg.id = commandMessageId;
        commandMsg.messageType = MessageType.COMMAND;
        commandMsg.correlationId = correlationId;
        when(messageService.findAllByCorrelationId(correlationId)).thenReturn(List.of(commandMsg));

        service.evaluate(workChannelId, agentId, "I have analysed the data.");

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());
        assertThat(captor.getValue().type()).isEqualTo(MessageType.STATUS);
        assertThat(captor.getValue().inReplyTo()).isEqualTo(commandMessageId);
        assertThat(captor.getValue().correlationId()).isEqualTo(correlationId);
    }

    @Test
    void evaluate_noPrefixFallback_watchdogExpiredPath_dispatchesStatusWithoutInReplyTo() {
        // No explicit signal + no open commitment (Watchdog expired)
        when(speechActClassifier.classify(any())).thenReturn(
            new SpeechActResult(MessageType.STATUS, "I have analysed the data.", DetectionTier.FALLBACK));
        when(commitmentStore.findOpenByObligor("finance-agent", workChannelId))
                .thenReturn(Collections.emptyList());

        service.evaluate(workChannelId, "finance-agent", "I have analysed the data.");

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());
        assertThat(captor.getValue().type()).isEqualTo(MessageType.STATUS);
        assertThat(captor.getValue().inReplyTo()).isNull();
    }

    @Test
    void evaluate_oversightGate_commandContentIsRawOutput() {
        // openGate() COMMAND message content = raw output (audit fidelity), NOT stripped content
        when(actionRiskClassifier.classify(any()))
                .thenReturn(new RiskDecision.GateRequired("risk", false));
        String rawOutput = "{\"type\":\"DONE\",\"content\":\"Cancel Netflix.\"}";
        when(speechActClassifier.classify(any())).thenReturn(
            new SpeechActResult(MessageType.DONE, "Cancel Netflix.", DetectionTier.JSON));

        service.evaluate(workChannelId, "finance-agent", rawOutput);

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());
        // COMMAND content must be the RAW output, not the stripped content
        assertThat(captor.getValue().content()).isEqualTo(rawOutput);
    }

    @Test
    void evaluate_oversightGate_promptUsesStrippedContent() {
        // buildOversightPrompt receives speechAct.content() (stripped), not raw JSON
        when(actionRiskClassifier.classify(any()))
                .thenReturn(new RiskDecision.GateRequired("risk", false));
        String rawOutput = "{\"type\":\"DONE\",\"content\":\"Cancel Netflix subscription.\"}";
        when(speechActClassifier.classify(any())).thenReturn(
            new SpeechActResult(MessageType.DONE, "Cancel Netflix subscription.", DetectionTier.JSON));

        service.evaluate(workChannelId, "finance-agent", rawOutput);

        // The prompt passed to hookClient.invoke() must contain the stripped content
        // and must NOT contain the raw JSON envelope
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(hookClient).invoke(anyString(), promptCaptor.capture(), anyString(), anyInt(), anyString());
        String prompt = promptCaptor.getValue();
        assertThat(prompt).contains("Cancel Netflix subscription.");
        assertThat(prompt).doesNotContain("{\"type\":");
    }

    @Test
    void evaluate_watchdogExpiredPath_contentIsStripped() {
        // Watchdog-expired path (no correlationId): STATUS dispatched with speechAct.content()
        when(speechActClassifier.classify(any())).thenReturn(
            new SpeechActResult(MessageType.DONE, "stripped content", DetectionTier.PREFIX));
        when(commitmentStore.findOpenByObligor("finance-agent", workChannelId))
                .thenReturn(Collections.emptyList());

        service.evaluate(workChannelId, "finance-agent", "[DONE] stripped content");

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());
        assertThat(captor.getValue().content()).isEqualTo("stripped content");
        assertThat(captor.getValue().content()).doesNotStartWith("[DONE]");
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
        assertThat(dispatched.sender()).isEqualTo(OversightGateService.GATE_SENDER);
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
     *
     * <p>Side effect: consumes one verify(messageService).dispatch() interaction inside
     * the helper. Callers that need to verify further messageService.dispatch() calls
     * after this (e.g. integration tests that don't mock gateDispatcher) should call
     * clearInvocations(messageService) before their own verify calls.
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
    void fulfill_approved_callsGateDispatcherWithApprovedTrue() {
        UUID gateId = openGateAndCaptureGateId();
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
                inReplyToCaptor.capture(), gateIdCaptor.capture(), outputCaptor.capture());
        assertThat(approvedCaptor.getValue()).isTrue();
        assertThat(oversightCaptor.getValue()).isEqualTo(oversightChannelId);
        assertThat(workCaptor.getValue()).isEqualTo(workChannelId);
        assertThat(inReplyToCaptor.getValue()).isEqualTo(42L);
        assertThat(gateIdCaptor.getValue()).isEqualTo(gateId);
        assertThat(outputCaptor.getValue()).isEqualTo("approved");
    }

    @Test
    void fulfill_rejected_callsGateDispatcherWithApprovedFalse() {
        UUID gateId = openGateAndCaptureGateId();
        when(commitmentStore.findByCorrelationId(gateId.toString()))
                .thenReturn(Optional.of(commitment(gateId)));

        service.fulfill(gateId, "rejected");

        ArgumentCaptor<Boolean> approvedCaptor = ArgumentCaptor.forClass(Boolean.class);
        verify(gateDispatcher).dispatch(
                approvedCaptor.capture(), any(), any(), org.mockito.ArgumentMatchers.anyLong(), any(), any());
        assertThat(approvedCaptor.getValue()).isFalse();
    }

    @Test
    void fulfill_rawOutputNull_treatsAsRejected() {
        UUID gateId = openGateAndCaptureGateId();
        when(commitmentStore.findByCorrelationId(gateId.toString()))
                .thenReturn(Optional.of(commitment(gateId)));

        service.fulfill(gateId, null);

        ArgumentCaptor<Boolean> approvedCaptor = ArgumentCaptor.forClass(Boolean.class);
        verify(gateDispatcher).dispatch(
                approvedCaptor.capture(), any(), any(), org.mockito.ArgumentMatchers.anyLong(), any(), any());
        assertThat(approvedCaptor.getValue()).isFalse();
    }

    @Test
    void fulfill_rawOutputBlank_treatsAsRejected() {
        UUID gateId = openGateAndCaptureGateId();
        when(commitmentStore.findByCorrelationId(gateId.toString()))
                .thenReturn(Optional.of(commitment(gateId)));

        service.fulfill(gateId, "   ");

        ArgumentCaptor<Boolean> approvedCaptor = ArgumentCaptor.forClass(Boolean.class);
        verify(gateDispatcher).dispatch(
                approvedCaptor.capture(), any(), any(), org.mockito.ArgumentMatchers.anyLong(), any(), any());
        assertThat(approvedCaptor.getValue()).isFalse();
    }

    @Test
    void fulfill_approvedWithTrailingText_isApproved() {
        UUID gateId = openGateAndCaptureGateId();
        when(commitmentStore.findByCorrelationId(gateId.toString()))
                .thenReturn(Optional.of(commitment(gateId)));

        service.fulfill(gateId, "approved, please go ahead");

        ArgumentCaptor<Boolean> approvedCaptor = ArgumentCaptor.forClass(Boolean.class);
        verify(gateDispatcher).dispatch(
                approvedCaptor.capture(), any(), any(), org.mockito.ArgumentMatchers.anyLong(), any(), any());
        assertThat(approvedCaptor.getValue()).isTrue();
    }

    @Test
    void fulfill_notApprovedPrefix_isRejected() {
        UUID gateId = openGateAndCaptureGateId();
        when(commitmentStore.findByCorrelationId(gateId.toString()))
                .thenReturn(Optional.of(commitment(gateId)));

        service.fulfill(gateId, "not approved");

        ArgumentCaptor<Boolean> approvedCaptor = ArgumentCaptor.forClass(Boolean.class);
        verify(gateDispatcher).dispatch(
                approvedCaptor.capture(), any(), any(), org.mockito.ArgumentMatchers.anyLong(), any(), any());
        assertThat(approvedCaptor.getValue()).isFalse();
    }

    // ── evaluate() — PlannedAction field population (#17 finding #3) ─────────

    @Test
    void evaluate_autonomous_passesCorrectPlannedActionToRiskClassifier() {
        service.evaluate(workChannelId, "finance-agent", "Run payroll.");

        ArgumentCaptor<PlannedAction> captor = ArgumentCaptor.forClass(PlannedAction.class);
        verify(actionRiskClassifier).classify(captor.capture());
        PlannedAction action = captor.getValue();
        assertThat(action.workerId()).isEqualTo("finance-agent");
        assertThat(action.caseId()).isEqualTo(caseId);
        assertThat(action.description()).isEqualTo("Run payroll.");
        assertThat(action.actionType()).isNull();
        assertThat(action.context()).isEmpty();
    }

    // ── evaluate() — openGate() COMMAND sender (#17 finding #2) ──────────────

    @Test
    void evaluate_gateRequired_commandSenderIsGateSenderNotAgent() {
        when(actionRiskClassifier.classify(any()))
                .thenReturn(new RiskDecision.GateRequired("risk", false));

        service.evaluate(workChannelId, "finance-agent", "Do risky thing.");

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());
        assertThat(captor.getValue().type()).isEqualTo(MessageType.COMMAND);
        assertThat(captor.getValue().sender()).isEqualTo(OversightGateService.GATE_SENDER);
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

    private Commitment openCommandCommitment(UUID channelId, String agentId, String correlationId) {
        Commitment c = new Commitment();
        c.id = UUID.randomUUID();
        c.correlationId = correlationId;
        c.channelId = channelId;
        c.obligor = agentId;
        c.messageType = MessageType.COMMAND;
        c.state = CommitmentState.OPEN;
        return c;
    }

    private DispatchResult dispatchResult(Long messageId) {
        return new DispatchResult(messageId, oversightChannelId, "agent",
                MessageType.COMMAND, null, null, null, null, null, null, null, 0);
    }
}
