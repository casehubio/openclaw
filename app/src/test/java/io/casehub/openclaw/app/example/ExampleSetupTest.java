package io.casehub.openclaw.app.example;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.casehub.api.model.CaseChannel;
import io.casehub.openclaw.casehub.OpenClawAgentRegistry;
import io.casehub.openclaw.casehub.OpenClawCaseChannelProvider;
import io.casehub.openclaw.context.ChannelContextWindowService;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.message.MessageService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExampleSetupTest {

    OpenClawCaseChannelProvider caseChannelProvider;
    OpenClawAgentRegistry registry;
    ChannelContextWindowService contextService;
    MessageService messageService;
    ExampleSetup setup;

    UUID caseId = UUID.fromString("00000001-0000-0000-0000-000000000001");
    UUID workChannelId = UUID.randomUUID();
    UUID observeChannelId = UUID.randomUUID();
    UUID oversightChannelId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        caseChannelProvider = mock(OpenClawCaseChannelProvider.class);
        registry = mock(OpenClawAgentRegistry.class);
        contextService = mock(ChannelContextWindowService.class);
        messageService = mock(MessageService.class);
        setup = new ExampleSetup(caseChannelProvider, registry, contextService, messageService);

        when(caseChannelProvider.openChannel(caseId, "work"))
                .thenReturn(caseChannel(workChannelId, "work"));
        when(caseChannelProvider.openChannel(caseId, "observe"))
                .thenReturn(caseChannel(observeChannelId, "observe"));
        when(caseChannelProvider.openChannel(caseId, "oversight"))
                .thenReturn(caseChannel(oversightChannelId, "oversight"));
    }

    @Test
    void opensAllThreeChannels() {
        setup.setupAndDispatch(caseId, "demo", "planner", "planner-key", "corr-1", "task");

        verify(caseChannelProvider).openChannel(caseId, "work");
        verify(caseChannelProvider).openChannel(caseId, "observe");
        verify(caseChannelProvider).openChannel(caseId, "oversight");
    }

    @Test
    void registersAgentWithCorrectParams() {
        setup.setupAndDispatch(caseId, "demo", "planner", "planner-key", "corr-1", "task");

        verify(registry).register("planner", "demo", caseId, "planner-key");
        verify(contextService).bindAgent("planner", caseId);
    }

    @Test
    void dispatchesCommandToWorkChannel() {
        final ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        setup.setupAndDispatch(caseId, "demo", "planner", "planner-key", "corr-1", "Review issue #42.");

        verify(messageService).dispatch(captor.capture());
        final MessageDispatch dispatch = captor.getValue();
        assertThat(dispatch.channelId()).isEqualTo(workChannelId);
        assertThat(dispatch.type()).isEqualTo(MessageType.COMMAND);
        assertThat(dispatch.content()).isEqualTo("Review issue #42.");
        assertThat(dispatch.correlationId()).isEqualTo("corr-1");
        assertThat(dispatch.actorType()).isEqualTo(ActorType.SYSTEM);
    }

    @Test
    void workChannelIdComesFromOpenChannelReturnValue() {
        // Verify the work channel UUID is extracted from openChannel() return value,
        // not from a subsequent listChannels() call.
        setup.setupAndDispatch(caseId, "demo", "planner", "planner-key", "corr-1", "task");

        final ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());
        assertThat(captor.getValue().channelId()).isEqualTo(workChannelId);
    }

    private static CaseChannel caseChannel(final UUID id, final String purpose) {
        return new CaseChannel(id.toString(), "case-demo/" + purpose, purpose, "qhorus", java.util.Map.of());
    }
}
