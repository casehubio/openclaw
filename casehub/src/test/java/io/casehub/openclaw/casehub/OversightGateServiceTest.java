package io.casehub.openclaw.casehub;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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

        service = new OversightGateService(channelService, messageService, commitmentStore, gateDispatcher);
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

    // ── helpers ───────────────────────────────────────────────────────────────

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
