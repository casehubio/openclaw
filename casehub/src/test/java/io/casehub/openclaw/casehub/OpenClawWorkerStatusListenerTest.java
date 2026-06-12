package io.casehub.openclaw.casehub;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.api.model.WorkResult;
import io.casehub.openclaw.context.ChannelContextWindowService;
import jakarta.enterprise.event.Event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class OpenClawWorkerStatusListenerTest {

    ChannelContextWindowService mockService;
    OpenClawAgentRegistry registry;
    Event<Object> mockEvents;
    OpenClawWorkerStatusListener listener;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        mockService = mock(ChannelContextWindowService.class);
        registry = new OpenClawAgentRegistry();
        mockEvents = mock(Event.class);
        listener = new OpenClawWorkerStatusListener(mockService, registry, mockEvents);
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

        verify(mockService).unbindAgent("agent-1", "test-tenant");
    }

    @Test
    void onWorkerCompleted_readsTenancyIdBeforeDeregister() {
        UUID caseId = UUID.randomUUID();
        registry.register("agent-1", "tenant-X", caseId, "sk");
        listener.onWorkerCompleted("agent-1", WorkResult.completed("key", Map.of(), "agent-1", caseId));
        verify(mockService).unbindAgent("agent-1", "tenant-X");
    }

    @Test
    void onWorkerCompleted_unknownAgent_unbindAgentWithNullTenancyId() {
        assertThatCode(() ->
            listener.onWorkerCompleted("unknown", WorkResult.completed("key", Map.of(), "unknown", UUID.randomUUID()))
        ).doesNotThrowAnyException();
        verify(mockService).unbindAgent(eq("unknown"), isNull());
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

        // Agent remains registered — Watchdog drives recovery
        assertThat(registry.findAgentId(caseId)).contains("agent-1");
    }

    @Test
    void onWorkerStarted_noExceptionThrown() {
        listener.onWorkerStarted("agent-1", Map.of("caseId", UUID.randomUUID().toString()));
    }
}
