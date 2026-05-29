package io.casehub.openclaw.casehub;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.api.model.CaseChannel;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.message.MessageService;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.openclaw.context.ChannelContextWindowService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenClawCaseChannelProviderTest {

    ChannelService channelService;
    MessageService messageService;
    ChannelContextWindowService mockContextService;
    OpenClawCaseChannelProvider provider;

    @BeforeEach
    void setup() {
        channelService = mock(ChannelService.class);
        messageService = mock(MessageService.class);
        mockContextService = mock(ChannelContextWindowService.class);
        provider = new OpenClawCaseChannelProvider(channelService, messageService, mockContextService);
    }

    private Channel channel(UUID id, String name) {
        Channel ch = new Channel();
        ch.id = id;
        ch.name = name;
        return ch;
    }

    @Test
    void openChannel_newChannel_callsCreate() {
        UUID caseId = UUID.randomUUID();
        UUID channelId = UUID.randomUUID();
        String name = CaseChannel.channelName(caseId, "work");
        when(channelService.findByName(name)).thenReturn(Optional.empty());
        when(channelService.create(anyString(), anyString(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(channel(channelId, name));

        CaseChannel result = provider.openChannel(caseId, "work");

        assertThat(result.id()).isEqualTo(channelId.toString());
        assertThat(result.purpose()).isEqualTo("work");
        assertThat(result.backendType()).isEqualTo("qhorus");
    }

    @Test
    void openChannel_existingChannel_returnsWithoutCreate() {
        UUID caseId = UUID.randomUUID();
        UUID channelId = UUID.randomUUID();
        String name = CaseChannel.channelName(caseId, "work");
        when(channelService.findByName(name)).thenReturn(Optional.of(channel(channelId, name)));

        CaseChannel result = provider.openChannel(caseId, "work");

        assertThat(result.id()).isEqualTo(channelId.toString());
        verify(channelService, never()).create(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void openChannel_callsBindChannel_onContextWindowService() {
        UUID caseId = UUID.randomUUID();
        UUID channelId = UUID.randomUUID();
        String name = CaseChannel.channelName(caseId, "work");
        when(channelService.findByName(name)).thenReturn(Optional.empty());
        when(channelService.create(anyString(), anyString(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(channel(channelId, name));

        provider.openChannel(caseId, "work");

        verify(mockContextService).bindChannel(caseId, channelId);
    }

    @Test
    void postToChannel_callsMessageServiceDispatch() {
        UUID channelId = UUID.randomUUID();
        CaseChannel ch = new CaseChannel(channelId.toString(), "case-x/work", "work", "qhorus", Map.of());

        provider.postToChannel(ch, "sender-1", "content", MessageType.COMMAND, "corr-1", null);

        verify(messageService).dispatch(any(MessageDispatch.class));
    }

    @Test
    void postToChannel_threeArgDefault_callsDispatch() {
        UUID channelId = UUID.randomUUID();
        CaseChannel ch = new CaseChannel(channelId.toString(), "case-x/work", "work", "qhorus", Map.of());

        provider.postToChannel(ch, "sender-1", "content");

        verify(messageService).dispatch(any(MessageDispatch.class));
    }

    @Test
    void postToChannel_nullType_defaultsToStatus_doesNotThrow() {
        UUID channelId = UUID.randomUUID();
        CaseChannel ch = new CaseChannel(channelId.toString(), "case-x/work", "work", "qhorus", Map.of());

        // null type would cause MessageDispatch builder to throw without the STATUS default
        provider.postToChannel(ch, "sender-1", "content", null, null, null);

        verify(messageService).dispatch(any(MessageDispatch.class));
    }

    @Test
    void closeChannel_noOp_doesNotThrow() {
        CaseChannel ch = new CaseChannel(UUID.randomUUID().toString(), "case-x/work", "work", "qhorus", Map.of());
        provider.closeChannel(ch); // must not throw
    }

    @Test
    void listChannels_delegatesToFindByNamePrefix() {
        UUID caseId = UUID.randomUUID();
        String prefix = "case-" + caseId + "/";
        Channel ch = channel(UUID.randomUUID(), prefix + "work");
        when(channelService.findByNamePrefix(prefix)).thenReturn(List.of(ch));

        List<CaseChannel> result = provider.listChannels(caseId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).purpose()).isEqualTo("work");
    }
}
