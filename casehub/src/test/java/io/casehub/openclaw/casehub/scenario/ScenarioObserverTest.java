package io.casehub.openclaw.casehub.scenario;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.gateway.MessageObserver;
import io.casehub.qhorus.api.gateway.MessageReceivedEvent;
import io.casehub.qhorus.api.message.MessageType;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ScenarioObserverTest {

    ScenarioStateStore mockStore;
    ScenarioMetadataProvider mockMetadata;
    ScenarioObserver observer;
    UUID knownChannelId = UUID.randomUUID();

    @BeforeEach
    void setup() {
        mockStore = mock(ScenarioStateStore.class);
        mockMetadata = mock(ScenarioMetadataProvider.class);

        // Mock scenario metadata
        var tradingScenario = new ScenarioDef(
                "trading-oversight", "Trading Oversight", "desc",
                java.util.List.of(
                        new AgentDef("signal", "Signal Analyst", "desc", 1),
                        new AgentDef("risk", "Risk Assessor", "desc", 2)),
                "risk", UUID.randomUUID());
        when(mockMetadata.allScenarios()).thenReturn(Map.of("trading-oversight", tradingScenario));

        when(mockStore.scenarioForChannel(knownChannelId)).thenReturn(Optional.of("trading-oversight"));
        observer = new ScenarioObserver(mockStore, mockMetadata);
    }

    private MessageReceivedEvent event(UUID channelId, MessageType type, String senderId, String content) {
        String c = (type == MessageType.EVENT) ? null : content;
        return new MessageReceivedEvent(null, "test/channel", channelId, "demo", type, senderId, "corr-1", Instant.now(), c, null);
    }

    @Test
    void knownChannel_statusMessage_updatesStoreWithResolvedRole() {
        observer.onMessage(event(knownChannelId, MessageType.STATUS, "signal", "analysis complete"));
        verify(mockStore).addMessage(eq("trading-oversight"), eq("signal"), eq("Signal Analyst"), eq("analysis complete"));
    }

    @Test
    void knownChannel_statusMessage_unknownAgent_fallsBackToSenderId() {
        observer.onMessage(event(knownChannelId, MessageType.STATUS, "unknown-agent", "content"));
        verify(mockStore).addMessage(eq("trading-oversight"), eq("unknown-agent"), eq("unknown-agent"), eq("content"));
    }

    @Test
    void unknownChannel_ignored() {
        observer.onMessage(event(UUID.randomUUID(), MessageType.STATUS, "signal", "ignored"));
        verify(mockStore, never()).addMessage(any(), any(), any(), any());
    }

    @Test
    void eventMessages_ignored() {
        observer.onMessage(event(knownChannelId, MessageType.EVENT, "signal", null));
        verify(mockStore, never()).addMessage(any(), any(), any(), any());
    }

    @Test
    void storeException_caughtNotPropagated() {
        doThrow(new RuntimeException("simulated")).when(mockStore).addMessage(any(), any(), any(), any());
        assertThatCode(() -> observer.onMessage(event(knownChannelId, MessageType.STATUS, "signal", "fail")))
                .doesNotThrowAnyException();
    }

    @Test
    void scope_returnsLocal() {
        assertThat(observer.scope()).isEqualTo(MessageObserver.Scope.LOCAL);
    }

    // Gate detection tests

    @Test
    void oversightChannel_commandFromGateAgent_firesGatePending() {
        // "risk" is the gateAgentId for trading-oversight
        observer.onMessage(event(knownChannelId, MessageType.COMMAND, "risk",
                "{\"action\":\"BUY NVDA\",\"classification\":\"high\",\"priorAgents\":\"[]\"}"));
        verify(mockStore).fireGatePending(eq("trading-oversight"), any(), eq("risk"),
                eq("BUY NVDA"), eq("high"), eq("[]"));
        verify(mockStore, never()).addMessage(any(), any(), any(), any());
    }

    @Test
    void oversightChannel_responseFromGateAgent_firesGateResolved() {
        observer.onMessage(event(knownChannelId, MessageType.RESPONSE, "risk", "approved"));
        verify(mockStore).fireGateResolved(eq("trading-oversight"), any(), eq("approved"));
        verify(mockStore, never()).addMessage(any(), any(), any(), any());
    }

    @Test
    void oversightChannel_declineFromGateAgent_firesGateResolved() {
        observer.onMessage(event(knownChannelId, MessageType.DECLINE, "risk", "rejected"));
        verify(mockStore).fireGateResolved(eq("trading-oversight"), any(), eq("rejected"));
        verify(mockStore, never()).addMessage(any(), any(), any(), any());
    }

    @Test
    void nonGateAgent_commandMessage_treatedAsNormalMessage() {
        // "signal" is NOT the gateAgentId
        observer.onMessage(event(knownChannelId, MessageType.COMMAND, "signal", "task content"));
        verify(mockStore).addMessage(eq("trading-oversight"), eq("signal"), eq("Signal Analyst"), eq("task content"));
        verify(mockStore, never()).fireGatePending(any(), any(), any(), any(), any(), any());
    }

    @Test
    void gateAgent_malformedJson_fallsBackToRawContent() {
        // Invalid JSON content should fall back to treating the whole content as action
        observer.onMessage(event(knownChannelId, MessageType.COMMAND, "risk", "not valid json"));
        verify(mockStore).fireGatePending(eq("trading-oversight"), any(), eq("risk"),
                eq("not valid json"), eq(""), eq(""));
    }

    @Test
    void gateAgent_partialJson_extractsAvailableFields() {
        // JSON with only some fields present
        observer.onMessage(event(knownChannelId, MessageType.COMMAND, "risk",
                "{\"action\":\"BUY TSLA\",\"classification\":\"medium\"}"));
        verify(mockStore).fireGatePending(eq("trading-oversight"), any(), eq("risk"),
                eq("BUY TSLA"), eq("medium"), eq(""));
    }
}
