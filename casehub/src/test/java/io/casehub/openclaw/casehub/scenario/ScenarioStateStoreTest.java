package io.casehub.openclaw.casehub.scenario;

import java.util.*;
import java.util.concurrent.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ScenarioStateStoreTest {

    ScenarioMetadataProvider metadata = new ScenarioMetadataProvider();
    ScenarioStateStore store;
    List<CaseExecutionEvent> receivedEvents;

    @BeforeEach
    void setup() {
        store = new ScenarioStateStore(metadata);
        receivedEvents = Collections.synchronizedList(new ArrayList<>());
        store.addListener(receivedEvents::add);
    }

    @Test
    void updateAgentState_running_broadcastsAgentStarted() {
        store.updateAgentState("trading-oversight", "signal", "running", 0, "OPEN");
        assertThat(receivedEvents).hasSize(1);
        assertThat(receivedEvents.get(0)).isInstanceOf(CaseExecutionEvent.AgentStartedEvent.class);
        var event = (CaseExecutionEvent.AgentStartedEvent) receivedEvents.get(0);
        assertThat(event.scenarioId()).isEqualTo("trading-oversight");
        assertThat(event.agentId()).isEqualTo("signal");
        assertThat(event.role()).isEqualTo("Signal Analyst");
    }

    @Test
    void updateAgentState_completed_broadcastsAgentCompleted() {
        store.updateAgentState("trading-oversight", "signal", "completed", 5000, "FULFILLED");
        assertThat(receivedEvents).hasSize(1);
        assertThat(receivedEvents.get(0)).isInstanceOf(CaseExecutionEvent.AgentCompletedEvent.class);
        var event = (CaseExecutionEvent.AgentCompletedEvent) receivedEvents.get(0);
        assertThat(event.outcome()).isEqualTo("completed");
        assertThat(event.durationMs()).isEqualTo(5000);
    }

    @Test
    void addMessage_broadcastsChannelMessageEvent() {
        store.addMessage("trading-oversight", "signal", "Signal Analyst", "NVDA breakout detected");
        assertThat(receivedEvents).hasSize(1);
        assertThat(receivedEvents.get(0)).isInstanceOf(CaseExecutionEvent.ChannelMessageEvent.class);
        var event = (CaseExecutionEvent.ChannelMessageEvent) receivedEvents.get(0);
        assertThat(event.content()).isEqualTo("NVDA breakout detected");
        assertThat(event.role()).isEqualTo("Signal Analyst");
    }

    @Test
    void isRunning_falseByDefault() {
        assertThat(store.isRunning("trading-oversight")).isFalse();
    }

    @Test
    void updateScenarioStatus_toRunning_broadcastsScenarioStarted() {
        store.updateScenarioStatus("trading-oversight", "running", "signal");
        assertThat(store.isRunning("trading-oversight")).isTrue();
        assertThat(receivedEvents).hasSize(1);
        assertThat(receivedEvents.get(0)).isInstanceOf(CaseExecutionEvent.ScenarioStartedEvent.class);
    }

    @Test
    void updateScenarioStatus_toCompleted_broadcastsScenarioCompleted() {
        store.updateScenarioStatus("trading-oversight", "running", "signal");
        receivedEvents.clear();
        store.updateScenarioStatus("trading-oversight", "completed", null);
        assertThat(store.isRunning("trading-oversight")).isFalse();
        assertThat(receivedEvents).hasSize(1);
        assertThat(receivedEvents.get(0)).isInstanceOf(CaseExecutionEvent.ScenarioCompletedEvent.class);
    }

    @Test
    void updateScenarioStatus_toFailed_broadcastsScenarioFailed() {
        store.updateScenarioStatus("trading-oversight", "running", "signal");
        receivedEvents.clear();
        store.updateScenarioStatus("trading-oversight", "failed", null);
        assertThat(receivedEvents).hasSize(1);
        var event = (CaseExecutionEvent.ScenarioFailedEvent) receivedEvents.get(0);
        assertThat(event.scenarioId()).isEqualTo("trading-oversight");
    }

    @Test
    void updateCommitment_broadcastsCommitmentUpdatedEvent() {
        store.updateCommitment("trading-oversight", "c-1", "signal", "OPEN", "");
        assertThat(receivedEvents).hasSize(1);
        assertThat(receivedEvents.get(0)).isInstanceOf(CaseExecutionEvent.CommitmentUpdatedEvent.class);
    }

    @Test
    void fireGatePending_broadcastsGatePendingEvent() {
        store.fireGatePending("trading-oversight", "g-1", "execution",
                "BUY 100 NVDA @ $892", "execution agent requires oversight",
                "[{\"agentId\":\"signal\"}]");
        assertThat(receivedEvents).hasSize(1);
        assertThat(receivedEvents.get(0)).isInstanceOf(CaseExecutionEvent.GatePendingEvent.class);
        var event = (CaseExecutionEvent.GatePendingEvent) receivedEvents.get(0);
        assertThat(event.gateId()).isEqualTo("g-1");
        assertThat(event.action()).isEqualTo("BUY 100 NVDA @ $892");
    }

    @Test
    void fireGateResolved_broadcastsGateResolvedEvent() {
        store.fireGatePending("trading-oversight", "g-1", "execution", "BUY", "oversight", "[]");
        receivedEvents.clear();
        store.fireGateResolved("trading-oversight", "g-1", "approved");
        assertThat(receivedEvents).hasSize(1);
        var event = (CaseExecutionEvent.GateResolvedEvent) receivedEvents.get(0);
        assertThat(event.decision()).isEqualTo("approved");
    }

    @Test
    void currentState_returnsSnapshot() {
        store.updateScenarioStatus("trading-oversight", "running", "signal");
        store.updateAgentState("trading-oversight", "signal", "running", 0, "OPEN");
        store.addMessage("trading-oversight", "signal", "Signal Analyst", "test msg");

        var snapshot = store.currentState("trading-oversight");
        assertThat(snapshot.scenarioId()).isEqualTo("trading-oversight");
        assertThat(snapshot.status()).isEqualTo("running");
        assertThat(snapshot.agents()).hasSize(1);
        assertThat(snapshot.agents().get(0).agentId()).isEqualTo("signal");
        assertThat(snapshot.recentMessages()).hasSize(1);
        assertThat(snapshot.pendingGate()).isNull();
    }

    @Test
    void currentState_withPendingGate_includesGate() {
        store.updateScenarioStatus("trading-oversight", "running", "signal");
        store.fireGatePending("trading-oversight", "g-1", "execution", "BUY", "oversight", "[]");

        var snapshot = store.currentState("trading-oversight");
        assertThat(snapshot.pendingGate()).isNotNull();
        assertThat(snapshot.pendingGate().gateId()).isEqualTo("g-1");
    }

    @Test
    void currentState_unknownScenario_returnsIdleSnapshot() {
        var snapshot = store.currentState("nonexistent");
        assertThat(snapshot.status()).isEqualTo("idle");
        assertThat(snapshot.agents()).isEmpty();
    }

    @Test
    void resetScenario_clearsState() {
        store.updateScenarioStatus("trading-oversight", "running", "signal");
        store.updateAgentState("trading-oversight", "signal", "completed", 5000, "FULFILLED");
        store.addMessage("trading-oversight", "signal", "Signal Analyst", "done");
        store.resetScenario("trading-oversight");

        assertThat(store.isRunning("trading-oversight")).isFalse();
        var snapshot = store.currentState("trading-oversight");
        assertThat(snapshot.status()).isEqualTo("idle");
        assertThat(snapshot.agents()).isEmpty();
        assertThat(snapshot.recentMessages()).isEmpty();
    }

    @Test
    void listScenarioSummaries_returnsAllScenarios() {
        var summaries = store.listScenarioSummaries();
        assertThat(summaries).hasSize(3);
        assertThat(summaries).anyMatch(s -> s.scenarioId().equals("trading-oversight") && s.status().equals("idle"));
    }

    @Test
    void removeListener_stopsReceiving() {
        List<CaseExecutionEvent> secondListener = Collections.synchronizedList(new ArrayList<>());
        ScenarioEventListener listener = secondListener::add;
        store.addListener(listener);

        store.addMessage("trading-oversight", "signal", "Signal Analyst", "test1");
        assertThat(secondListener).hasSize(1);

        store.removeListener(listener);
        store.addMessage("trading-oversight", "signal", "Signal Analyst", "test2");
        assertThat(secondListener).hasSize(1);
        assertThat(receivedEvents).hasSize(2);
    }

    @Test
    void listenerException_doesNotPropagateOrBlockOthers() {
        List<CaseExecutionEvent> secondListener = Collections.synchronizedList(new ArrayList<>());
        store.addListener(event -> { throw new RuntimeException("bad listener"); });
        store.addListener(secondListener::add);

        store.addMessage("trading-oversight", "signal", "Signal Analyst", "test");
        assertThat(secondListener).hasSize(1);
        assertThat(receivedEvents).hasSize(1);
    }

    @Test
    void registerChannel_scenarioForChannel_roundtrip() {
        var channelId = java.util.UUID.randomUUID();
        store.registerChannel(channelId, "trading-oversight");
        assertThat(store.scenarioForChannel(channelId)).isPresent().contains("trading-oversight");
    }
}
