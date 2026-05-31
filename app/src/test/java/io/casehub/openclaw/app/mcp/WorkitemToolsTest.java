package io.casehub.openclaw.app.mcp;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.message.MessageService;
import io.quarkiverse.mcp.server.ToolResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for casehub_create_workitem and casehub_queue tools.
 *
 * <p>These are stateless REST-style tools: one dispatch call, structured response.
 * WorkitemTools creates a CaseHub work item by dispatching a COMMAND to a "work"
 * channel for the queue or assignee.
 */
class WorkitemToolsTest {

    MessageService messageService;
    ChannelService channelService;
    WorkitemTools tools;

    @BeforeEach
    void setUp() {
        messageService = mock(MessageService.class);
        channelService = mock(ChannelService.class);
        tools = new WorkitemTools(messageService, channelService);
    }

    // ---- casehub_create_workitem ----

    @Test
    void createWorkitem_validInputs_dispatchesCommandToWorkChannel() {
        UUID channelId = UUID.randomUUID();
        Channel workChannel = channel(channelId, "work/general");
        when(channelService.findByNamePrefix("work/")).thenReturn(List.of(workChannel));
        when(messageService.dispatch(any()))
                .thenReturn(dispatchResult(7L, channelId, "casehub-openclaw", MessageType.COMMAND, null));

        ToolResponse response = tools.createWorkitem(
                "finance-agent", "Order groceries", "2026-06-04T17:00:00Z", null, null);

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());
        MessageDispatch dispatched = captor.getValue();
        assertThat(dispatched.type()).isEqualTo(MessageType.COMMAND);
        assertThat(dispatched.content()).contains("Order groceries");
        assertThat(dispatched.deadline()).isNotNull();

        assertThat(response.isError()).isFalse();
        assertThat(text(response)).contains("workitemId");
        assertThat(text(response)).contains("deadline");
    }

    @Test
    void createWorkitem_assigneeAndQueueBothProvided_returnsError() {
        ToolResponse response = tools.createWorkitem(
                "finance-agent", "Order groceries", "2026-06-04T17:00:00Z",
                "home-agent", "finance-queue");

        verify(messageService, never()).dispatch(any());
        assertThat(response.isError()).isTrue();
        assertThat(text(response)).contains("ASSIGNEE_AND_QUEUE_CONFLICT");
    }

    @Test
    void createWorkitem_invalidDeadline_returnsError() {
        ToolResponse response = tools.createWorkitem(
                "finance-agent", "Order groceries", "not-a-date", null, null);

        verify(messageService, never()).dispatch(any());
        assertThat(response.isError()).isTrue();
        assertThat(text(response)).contains("INVALID_DEADLINE");
    }

    @Test
    void createWorkitem_casehubUnavailable_returnsError() {
        when(channelService.findByNamePrefix("work/")).thenReturn(List.of());

        ToolResponse response = tools.createWorkitem(
                "finance-agent", "Order groceries", "2026-06-04T17:00:00Z", null, null);

        assertThat(response.isError()).isTrue();
        assertThat(text(response)).contains("CASEHUB_UNAVAILABLE");
    }

    @Test
    void createWorkitem_withAssignee_dispatchesCommandWithTarget() {
        UUID channelId = UUID.randomUUID();
        Channel workChannel = channel(channelId, "work/general");
        when(channelService.findByNamePrefix("work/")).thenReturn(List.of(workChannel));
        when(messageService.dispatch(any()))
                .thenReturn(dispatchResult(8L, channelId, "casehub-openclaw", MessageType.COMMAND, null));

        tools.createWorkitem("system", "Review contract", "2026-06-05T12:00:00Z", "legal-agent", null);

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());
        assertThat(captor.getValue().target()).isEqualTo("legal-agent");
    }

    // ---- casehub_queue ----

    @Test
    void queue_namedQueue_dispatchesCommandToNamedQueueChannel() {
        UUID channelId = UUID.randomUUID();
        Channel financeChannel = channel(channelId, "work/finance");
        when(channelService.findByName("work/finance")).thenReturn(Optional.of(financeChannel));
        when(messageService.dispatch(any()))
                .thenReturn(dispatchResult(9L, channelId, "casehub-openclaw", MessageType.COMMAND, null));

        ToolResponse response = tools.queue("finance-agent", "Review flagged transaction", "finance", "high");

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());
        assertThat(captor.getValue().channelId()).isEqualTo(channelId);
        assertThat(captor.getValue().type()).isEqualTo(MessageType.COMMAND);

        assertThat(response.isError()).isFalse();
        assertThat(text(response)).contains("workitemId");
        assertThat(text(response)).contains("finance");
    }

    @Test
    void queue_unknownQueueName_returnsErrorWithAvailableQueues() {
        when(channelService.findByName("work/unknown")).thenReturn(Optional.empty());
        when(channelService.findByNamePrefix("work/")).thenReturn(List.of(
                channel(UUID.randomUUID(), "work/finance"),
                channel(UUID.randomUUID(), "work/home")));

        ToolResponse response = tools.queue("finance-agent", "task", "unknown", null);

        assertThat(response.isError()).isTrue();
        String err = text(response);
        assertThat(err).contains("finance");
        assertThat(err).contains("home");
    }

    // ---- helpers ----

    private static Channel channel(UUID id, String name) {
        Channel c = new Channel();
        c.id = id;
        c.name = name;
        return c;
    }

    private static io.casehub.qhorus.api.message.DispatchResult dispatchResult(
            long messageId, UUID channelId, String sender, MessageType type, String correlationId) {
        return new io.casehub.qhorus.api.message.DispatchResult(
                messageId, channelId, sender, type, correlationId,
                null, List.of(), null, null, null, null, 0);
    }

    private static String text(ToolResponse response) {
        return response.content().stream()
                .filter(c -> c instanceof io.quarkiverse.mcp.server.TextContent)
                .map(c -> ((io.quarkiverse.mcp.server.TextContent) c).text())
                .findFirst()
                .orElse("");
    }
}
