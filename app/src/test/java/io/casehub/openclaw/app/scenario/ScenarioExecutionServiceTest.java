package io.casehub.openclaw.app.scenario;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.openclaw.app.example.ExamplePoller;
import io.casehub.openclaw.app.example.ExampleSetup;
import io.casehub.openclaw.casehub.OpenClawAgentConfigResolver;
import io.casehub.openclaw.casehub.scenario.*;
import io.casehub.qhorus.api.message.CommitmentState;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ScenarioExecutionServiceTest {

    ScenarioStateStore stateStore;
    ScenarioMetadataProvider metadata;
    ExampleSetup exampleSetup;
    ExamplePoller examplePoller;
    OpenClawAgentConfigResolver configResolver;
    ScenarioExecutionService service;

    @BeforeEach
    void setup() {
        metadata = new ScenarioMetadataProvider();
        stateStore = mock(ScenarioStateStore.class);
        exampleSetup = mock(ExampleSetup.class);
        examplePoller = mock(ExamplePoller.class);
        configResolver = mock(OpenClawAgentConfigResolver.class);

        // Configure agent config for trading-oversight agents
        var signalConfig = new OpenClawAgentConfigResolver.AgentConfig("signal-session", java.util.List.of());
        var riskConfig = new OpenClawAgentConfigResolver.AgentConfig("risk-session", java.util.List.of());
        var execConfig = new OpenClawAgentConfigResolver.AgentConfig("exec-session", java.util.List.of());
        when(configResolver.allAgents()).thenReturn(java.util.Map.of(
                "signal", signalConfig, "risk", riskConfig, "execution", execConfig));

        when(exampleSetup.setupAndDispatch(any(), any(), any(), any(), any(), any()))
                .thenReturn(new SetupResult(UUID.randomUUID(), UUID.randomUUID()));
        when(examplePoller.checkState(any())).thenReturn(CommitmentState.FULFILLED);
        when(stateStore.tryStart(any())).thenReturn(true);

        service = new ScenarioExecutionService(stateStore, metadata, exampleSetup,
                examplePoller, configResolver, true, "demo", 300);
    }

    @Test
    void start_unknownScenario_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.start("nonexistent"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown scenario");
    }

    @Test
    void start_alreadyRunning_throwsIllegalState() {
        when(stateStore.tryStart("trading-oversight")).thenReturn(false);
        assertThatThrownBy(() -> service.start("trading-oversight"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Already running");
    }

    @Test
    void start_resetsScenarioAndBroadcastsScenarioStarted() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(inv -> {
            latch.countDown();
            return null;
        }).when(stateStore).updateScenarioStatus(eq("trading-oversight"), eq("running"), any());

        service.start("trading-oversight");

        // Wait for async execution to start
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();

        verify(stateStore).resetScenario("trading-oversight");
        verify(stateStore).updateScenarioStatus(eq("trading-oversight"), eq("running"), any());
    }

    @Test
    void start_registersChannelsAfterSetup() throws Exception {
        var workId = UUID.randomUUID();
        var oversightId = UUID.randomUUID();
        when(exampleSetup.setupAndDispatch(any(), any(), any(), any(), any(), any()))
                .thenReturn(new SetupResult(workId, oversightId));

        CountDownLatch latch = new CountDownLatch(2);
        doAnswer(inv -> {
            latch.countDown();
            return null;
        }).when(stateStore).registerChannel(any(UUID.class), eq("trading-oversight"));

        service.start("trading-oversight");

        // Wait for channel registration
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();

        verify(stateStore).registerChannel(workId, "trading-oversight");
        verify(stateStore).registerChannel(oversightId, "trading-oversight");
    }

    @Test
    void start_sequencesAgentsInOrder() throws Exception {
        CountDownLatch latch = new CountDownLatch(3); // 3 agents
        doAnswer(inv -> {
            String state = inv.getArgument(2);
            if ("running".equals(state)) {
                latch.countDown();
            }
            return null;
        }).when(stateStore).updateAgentState(eq("trading-oversight"), any(), any(), anyLong(), any());

        service.start("trading-oversight");

        // Wait for all agents to start
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

        // Verify agents started in order
        verify(stateStore).updateAgentState(eq("trading-oversight"), eq("signal"), eq("running"), eq(0L), eq("OPEN"));
        verify(stateStore).updateAgentState(eq("trading-oversight"), eq("risk"), eq("running"), eq(0L), eq("OPEN"));
        verify(stateStore).updateAgentState(eq("trading-oversight"), eq("execution"), eq("running"), eq(0L), eq("OPEN"));
    }

    @Test
    void start_mapsCommitmentStateToOutcome() throws Exception {
        // FULFILLED → completed
        CountDownLatch completedLatch = new CountDownLatch(3);
        doAnswer(inv -> {
            String state = inv.getArgument(2);
            if (!"running".equals(state)) {
                completedLatch.countDown();
            }
            return null;
        }).when(stateStore).updateAgentState(eq("trading-oversight"), any(), any(), anyLong(), any());

        service.start("trading-oversight");

        assertThat(completedLatch.await(15, TimeUnit.SECONDS)).isTrue();

        // All agents should complete with "completed" outcome (mapped from FULFILLED)
        verify(stateStore, atLeastOnce()).updateAgentState(
                eq("trading-oversight"), any(), eq("completed"), anyLong(), eq("FULFILLED"));
    }

    @Test
    void start_nonFulfilledStopsPipelineAndBroadcastsFailed() throws Exception {
        // First agent succeeds, second declines
        when(examplePoller.checkState(any()))
                .thenReturn(CommitmentState.FULFILLED)  // signal succeeds
                .thenReturn(CommitmentState.DECLINED);  // risk declines

        CountDownLatch failedLatch = new CountDownLatch(1);
        doAnswer(inv -> {
            String status = inv.getArgument(1);
            if ("failed".equals(status)) {
                failedLatch.countDown();
            }
            return null;
        }).when(stateStore).updateScenarioStatus(eq("trading-oversight"), any(), any());

        service.start("trading-oversight");

        assertThat(failedLatch.await(5, TimeUnit.SECONDS)).isTrue();

        // Verify scenario failed
        verify(stateStore).updateScenarioStatus(eq("trading-oversight"), eq("failed"), any());

        // Execution agent should NOT have been started (pipeline stopped)
        verify(stateStore, never()).updateAgentState(eq("trading-oversight"), eq("execution"), any(), anyLong(), any());
    }

    @Test
    void start_allAgentsSucceed_broadcastsCompleted() throws Exception {
        CountDownLatch completedLatch = new CountDownLatch(1);
        doAnswer(inv -> {
            String status = inv.getArgument(1);
            if ("completed".equals(status)) {
                completedLatch.countDown();
            }
            return null;
        }).when(stateStore).updateScenarioStatus(eq("trading-oversight"), any(), any());

        service.start("trading-oversight");

        assertThat(completedLatch.await(15, TimeUnit.SECONDS)).isTrue();

        verify(stateStore).updateScenarioStatus(eq("trading-oversight"), eq("completed"), isNull());
    }

    @Test
    void start_updatesCommitmentAfterAgentCompletes() throws Exception {
        CountDownLatch latch = new CountDownLatch(3); // 3 agents
        doAnswer(inv -> {
            latch.countDown();
            return null;
        }).when(stateStore).updateCommitment(eq("trading-oversight"), any(), any(), any(), any());

        service.start("trading-oversight");

        assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue();

        verify(stateStore, times(3)).updateCommitment(
                eq("trading-oversight"), any(), any(), eq("FULFILLED"), eq("completed"));
    }
}
