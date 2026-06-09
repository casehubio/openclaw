package io.casehub.openclaw.casehub;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.message.DispatchResult;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.message.MessageService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OversightGateDispatcherTest {

    MessageService messageService;
    OversightGateDispatcher dispatcher;

    UUID oversightChannelId = UUID.randomUUID();
    UUID workChannelId = UUID.randomUUID();
    UUID gateId = UUID.randomUUID();
    String originalCommitmentId = UUID.randomUUID().toString();
    long originalCommandMsgId = 99L;
    GateContext gateContext = new GateContext(originalCommitmentId, workChannelId, originalCommandMsgId);

    @BeforeEach
    void setup() {
        messageService = mock(MessageService.class);
        dispatcher = new OversightGateDispatcher(messageService);
        when(messageService.dispatch(any())).thenReturn(dispatchResult(1L));
    }

    // ── no-context fallback (existing behaviour) ────────────────────────────

    @Test
    void dispatch_approved_noContext_sendsResponseToOversightAndStatusToWork() {
        dispatcher.dispatch(true, oversightChannelId, workChannelId, 42L, gateId, "approved",
                Optional.empty());

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService, times(2)).dispatch(captor.capture());

        MessageDispatch oversight = captor.getAllValues().get(0);
        assertThat(oversight.channelId()).isEqualTo(oversightChannelId);
        assertThat(oversight.type()).isEqualTo(MessageType.RESPONSE);
        assertThat(oversight.sender()).isEqualTo(OversightGateService.GATE_SENDER);
        assertThat(oversight.correlationId()).isEqualTo(gateId.toString());
        assertThat(oversight.inReplyTo()).isEqualTo(42L);
        assertThat(oversight.content()).isEqualTo("approved");
        assertThat(oversight.actorType()).isEqualTo(ActorType.AGENT);

        MessageDispatch work = captor.getAllValues().get(1);
        assertThat(work.channelId()).isEqualTo(workChannelId);
        assertThat(work.type()).isEqualTo(MessageType.STATUS);
        assertThat(work.sender()).isEqualTo(OversightGateService.GATE_SENDER);
        assertThat(work.actorType()).isEqualTo(ActorType.AGENT);
    }

    @Test
    void dispatch_rejected_noContext_sendsDeclineToOversightAndStatusToWork() {
        dispatcher.dispatch(false, oversightChannelId, workChannelId, 42L, gateId, "rejected",
                Optional.empty());

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService, times(2)).dispatch(captor.capture());

        MessageDispatch oversight = captor.getAllValues().get(0);
        assertThat(oversight.channelId()).isEqualTo(oversightChannelId);
        assertThat(oversight.type()).isEqualTo(MessageType.DECLINE);
        assertThat(oversight.sender()).isEqualTo(OversightGateService.GATE_SENDER);
        assertThat(oversight.correlationId()).isEqualTo(gateId.toString());
        assertThat(oversight.inReplyTo()).isEqualTo(42L);
        assertThat(oversight.content()).isEqualTo("rejected");
        assertThat(oversight.actorType()).isEqualTo(ActorType.AGENT);

        MessageDispatch work = captor.getAllValues().get(1);
        assertThat(work.channelId()).isEqualTo(workChannelId);
        assertThat(work.type()).isEqualTo(MessageType.STATUS);
        assertThat(work.sender()).isEqualTo(OversightGateService.GATE_SENDER);
        assertThat(work.actorType()).isEqualTo(ActorType.AGENT);
    }

    @Test
    void dispatch_approved_noContext_nullOutput_contentDefaultsToApproved() {
        dispatcher.dispatch(true, oversightChannelId, workChannelId, 42L, gateId, null,
                Optional.empty());

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService, times(2)).dispatch(captor.capture());
        assertThat(captor.getAllValues().get(0).content()).isEqualTo("approved");
    }

    @Test
    void dispatch_rejected_noContext_nullOutput_contentDefaultsToRejected() {
        dispatcher.dispatch(false, oversightChannelId, workChannelId, 42L, gateId, null,
                Optional.empty());

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService, times(2)).dispatch(captor.capture());
        assertThat(captor.getAllValues().get(0).content()).isEqualTo("rejected");
    }

    // ── with context (new behaviour) ────────────────────────────────────────

    @Test
    void dispatch_approved_withContext_sendsResponseToOversightAndDoneToWork() {
        dispatcher.dispatch(true, oversightChannelId, workChannelId, 42L, gateId, "approved",
                Optional.of(gateContext));

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService, times(2)).dispatch(captor.capture());

        MessageDispatch oversight = captor.getAllValues().get(0);
        assertThat(oversight.channelId()).isEqualTo(oversightChannelId);
        assertThat(oversight.type()).isEqualTo(MessageType.RESPONSE);
        assertThat(oversight.correlationId()).isEqualTo(gateId.toString());
        assertThat(oversight.inReplyTo()).isEqualTo(42L);

        MessageDispatch work = captor.getAllValues().get(1);
        assertThat(work.channelId()).isEqualTo(workChannelId);
        assertThat(work.type()).isEqualTo(MessageType.DONE);
        assertThat(work.correlationId()).isEqualTo(originalCommitmentId);
        assertThat(work.inReplyTo()).isEqualTo(originalCommandMsgId);
        assertThat(work.sender()).isEqualTo(OversightGateService.GATE_SENDER);
        assertThat(work.actorType()).isEqualTo(ActorType.AGENT);
    }

    @Test
    void dispatch_rejected_withContext_sendsDeclineToOversightAndDeclineToWork() {
        dispatcher.dispatch(false, oversightChannelId, workChannelId, 42L, gateId, "rejected",
                Optional.of(gateContext));

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService, times(2)).dispatch(captor.capture());

        MessageDispatch oversight = captor.getAllValues().get(0);
        assertThat(oversight.type()).isEqualTo(MessageType.DECLINE);
        assertThat(oversight.correlationId()).isEqualTo(gateId.toString());

        MessageDispatch work = captor.getAllValues().get(1);
        assertThat(work.type()).isEqualTo(MessageType.DECLINE);
        assertThat(work.correlationId()).isEqualTo(originalCommitmentId);
        assertThat(work.inReplyTo()).isEqualTo(originalCommandMsgId);
    }

    @Test
    void dispatch_approved_withContext_twoDispatchCallsTotal() {
        dispatcher.dispatch(true, oversightChannelId, workChannelId, 42L, gateId, "approved",
                Optional.of(gateContext));
        verify(messageService, times(2)).dispatch(any());
    }

    private DispatchResult dispatchResult(Long messageId) {
        return new DispatchResult(messageId, oversightChannelId, OversightGateService.GATE_SENDER,
                MessageType.RESPONSE, null, null, null, null, null, null, null, 0);
    }
}
