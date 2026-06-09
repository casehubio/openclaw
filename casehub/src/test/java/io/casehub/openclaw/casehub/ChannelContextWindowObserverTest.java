package io.casehub.openclaw.casehub;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import io.casehub.openclaw.context.ChannelContextWindowService;
import io.casehub.qhorus.api.gateway.MessageObserver;
import io.casehub.qhorus.api.gateway.MessageReceivedEvent;
import io.casehub.qhorus.api.message.MessageType;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ChannelContextWindowObserverTest {

    ChannelContextWindowService mockService;
    ChannelContextWindowObserver observer;

    @BeforeEach
    void setup() {
        mockService = mock(ChannelContextWindowService.class);
        observer = new ChannelContextWindowObserver();
        observer.service = mockService; // package-private field injection
    }

    private MessageReceivedEvent event(MessageType type) {
        String content = (type == MessageType.EVENT) ? null : "content";
        return new MessageReceivedEvent(
                "test/channel", UUID.randomUUID(), "test-tenant", type, "sender", "corr-1", content);
    }

    @Test
    void eventMessages_notPassedToService() {
        observer.onMessage(event(MessageType.EVENT));
        verify(mockService, never()).add(any());
    }

    @ParameterizedTest
    @EnumSource(value = MessageType.class, mode = EnumSource.Mode.EXCLUDE, names = {"EVENT"})
    void agentVisibleTypes_passedToService(MessageType type) {
        MessageReceivedEvent e = event(type);
        observer.onMessage(e);
        verify(mockService).add(e);
    }

    @Test
    void serviceException_caughtNotPropagated() {
        doThrow(new RuntimeException("simulated failure")).when(mockService).add(any());
        assertThatCode(() -> observer.onMessage(event(MessageType.STATUS)))
                .doesNotThrowAnyException();
    }

    @Test
    void scope_returnsLocal() {
        assertThat(observer.scope()).isEqualTo(MessageObserver.Scope.LOCAL);
    }
}
