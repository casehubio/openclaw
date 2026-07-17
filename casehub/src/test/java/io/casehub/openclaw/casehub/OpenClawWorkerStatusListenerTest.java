package io.casehub.openclaw.casehub;

import io.casehub.api.model.WorkResult;
import io.casehub.openclaw.context.ChannelContextWindowService;
import jakarta.enterprise.event.Event;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class OpenClawWorkerStatusListenerTest {

    ChannelContextWindowService  mockService;
    OpenClawAgentRegistry        registry;
    Event<Object>                mockEvents;
    OpenClawWorkerStatusListener listener;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        mockService = mock(ChannelContextWindowService.class);
        registry    = new OpenClawAgentRegistry();
        mockEvents  = mock(Event.class);
        listener    = new OpenClawWorkerStatusListener(mockService, registry, mockEvents);
    }

    @Test
    void onWorkerCompleted_deregistersFromRegistry() {
        UUID caseId = UUID.randomUUID();
        registry.register("agent-1", "test-tenant", caseId, "sk");

        listener.onWorkerCompleted("agent-1", WorkResult.completed("key", Map.of(), "agent-1", caseId));

        assertThat(registry.findAgentId(caseId)).isEmpty();
    }

    @Test
    void onWorkerCompleted_callsUnbindAgentOnContextWindowService() {
        UUID caseId = UUID.randomUUID();
        registry.register("agent-1", "test-tenant", caseId, "sk");

        listener.onWorkerCompleted("agent-1", WorkResult.completed("key", Map.of(), "agent-1", caseId));

        verify(mockService).unbindAgent("agent-1");
    }

    @Test
    void onWorkerCompleted_unknownAgent_callsUnbindAgent() {
        assertThatCode(() ->
                               listener.onWorkerCompleted("unknown", WorkResult.completed("key", Map.of(), "unknown", UUID.randomUUID()))
                      ).doesNotThrowAnyException();
        verify(mockService).unbindAgent("unknown");
    }

    @Test
    void onWorkerCompleted_lastAgent_closesCase() {
        UUID caseId = UUID.randomUUID();
        registry.register("agent-1", "test-tenant", caseId, "sk");

        listener.onWorkerCompleted("agent-1", WorkResult.completed("key", Map.of(), "agent-1", caseId));

        verify(mockService).closeCase(caseId);
    }

    @Test
    void onWorkerCompleted_otherAgentsRemain_doesNotCloseCase() {
        UUID caseId = UUID.randomUUID();
        registry.register("agent-A", "test-tenant", caseId, "sk-A");
        registry.register("agent-B", "test-tenant", caseId, "sk-B");

        listener.onWorkerCompleted("agent-A", WorkResult.completed("key", Map.of(), "agent-A", caseId));

        verify(mockService, never()).closeCase(any());
        assertThat(registry.findAgentIds(caseId)).containsExactly("agent-B");
    }

    @Test
    void onWorkerStalled_firesWorkerStalledEvent() {
        listener.onWorkerStalled("agent-1");
        verify(mockEvents).fire(any(OpenClawWorkerStatusListener.WorkerStalledEvent.class));
    }

    @Test
    void onWorkerStalled_doesNotDeregisterFromRegistry() {
        UUID caseId = UUID.randomUUID();
        registry.register("agent-1", "test-tenant", caseId, "sk");

        listener.onWorkerStalled("agent-1");

        assertThat(registry.findAgentId(caseId)).contains("agent-1");
    }

    @Test
    void onWorkerStarted_noExceptionThrown() {
        listener.onWorkerStarted("agent-1", Map.of("caseId", UUID.randomUUID().toString()));
    }
}
