package io.casehub.openclaw.casehub;

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

    @BeforeEach
    void setup() {
        messageService = mock(MessageService.class);
        dispatcher = new OversightGateDispatcher(messageService);
        when(messageService.dispatch(any())).thenReturn(dispatchResult(1L));
    }

    @Test
    void dispatch_approved_sendsResponseToOversightThenStatusToWork() {
        dispatcher.dispatch(true, oversightChannelId, workChannelId, 42L, gateId, "approved");

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
    void dispatch_rejected_sendsDeclineToOversightThenStatusToWork() {
        dispatcher.dispatch(false, oversightChannelId, workChannelId, 42L, gateId, "rejected");

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
    void dispatch_approvedWithNullOutput_contentDefaultsToApproved() {
        dispatcher.dispatch(true, oversightChannelId, workChannelId, 42L, gateId, null);

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService, times(2)).dispatch(captor.capture());
        assertThat(captor.getAllValues().get(0).content()).isEqualTo("approved");
    }

    @Test
    void dispatch_rejectedWithNullOutput_contentDefaultsToRejected() {
        dispatcher.dispatch(false, oversightChannelId, workChannelId, 42L, gateId, null);

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService, times(2)).dispatch(captor.capture());
        assertThat(captor.getAllValues().get(0).content()).isEqualTo("rejected");
    }

    private DispatchResult dispatchResult(Long messageId) {
        return new DispatchResult(messageId, oversightChannelId, OversightGateService.GATE_SENDER,
                MessageType.RESPONSE, null, null, null, null, null, null, null, 0);
    }
}
