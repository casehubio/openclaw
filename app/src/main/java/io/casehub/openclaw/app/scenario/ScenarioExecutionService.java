package io.casehub.openclaw.app.scenario;

import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.casehub.openclaw.app.example.ExamplePoller;
import io.casehub.openclaw.app.example.ExampleSetup;
import io.casehub.openclaw.casehub.OpenClawAgentConfigResolver;
import io.casehub.openclaw.casehub.scenario.*;
import io.casehub.qhorus.api.message.CommitmentState;

/**
 * Async demo scenario execution service.
 * <p>
 * Sequences agents on virtual threads, broadcasts state transitions via ScenarioStateStore,
 * and registers channels for ScenarioObserver routing.
 * <p>
 * Execution model:
 * 1. Validates scenario exists and not already running
 * 2. Resets prior state (agents, messages, commitments, gates)
 * 3. Broadcasts SCENARIO_STARTED
 * 4. For each agent in order:
 *    a. Broadcasts AGENT_STARTED
 *    b. Calls ExampleSetup.setupAndDispatch() → SetupResult
 *    c. Registers work and oversight channels
 *    d. Polls ExamplePoller.checkState() until terminal
 *    e. Maps CommitmentState to outcome string
 *    f. Broadcasts AGENT_COMPLETED
 *    g. Broadcasts COMMITMENT_UPDATED
 *    h. Non-FULFILLED stops pipeline, broadcasts SCENARIO_FAILED
 * 5. After all agents: broadcasts SCENARIO_COMPLETED
 */
@ApplicationScoped
public class ScenarioExecutionService {

    private static final Logger LOG = Logger.getLogger(ScenarioExecutionService.class);

    private final ScenarioStateStore stateStore;
    private final ScenarioMetadataProvider metadata;
    private final ExampleSetup exampleSetup;
    private final ExamplePoller examplePoller;
    private final OpenClawAgentConfigResolver configResolver;
    private final boolean enabled;
    private final String tenancyId;
    private final long timeoutSeconds;

    @Inject
    public ScenarioExecutionService(
            ScenarioStateStore stateStore,
            ScenarioMetadataProvider metadata,
            ExampleSetup exampleSetup,
            ExamplePoller examplePoller,
            OpenClawAgentConfigResolver configResolver,
            @ConfigProperty(name = "casehub.example.enabled", defaultValue = "false") boolean enabled,
            @ConfigProperty(name = "casehub.example.tenancyid", defaultValue = "demo") String tenancyId,
            @ConfigProperty(name = "casehub.example.timeout.seconds", defaultValue = "300") long timeoutSeconds) {
        this.stateStore = stateStore;
        this.metadata = metadata;
        this.exampleSetup = exampleSetup;
        this.examplePoller = examplePoller;
        this.configResolver = configResolver;
        this.enabled = enabled;
        this.tenancyId = tenancyId;
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * Start async scenario execution.
     * <p>
     * Returns immediately — execution runs on a virtual thread.
     *
     * @param scenarioId demo scenario identifier
     * @throws IllegalArgumentException if scenarioId is unknown
     * @throws IllegalStateException if scenario is already running
     */
    public void start(String scenarioId) {
        var scenarioDef = metadata.allScenarios().get(scenarioId);
        if (scenarioDef == null) {
            throw new IllegalArgumentException("Unknown scenario: " + scenarioId);
        }
        if (!stateStore.tryStart(scenarioId)) {
            throw new IllegalStateException("Already running: " + scenarioId);
        }

        LOG.infof("Starting scenario '%s' (caseId=%s)", scenarioId, scenarioDef.caseId());
        Thread.startVirtualThread(() -> executeScenario(scenarioId, scenarioDef));
    }

    private void executeScenario(String scenarioId, ScenarioDef scenarioDef) {
        try {
            // Reset prior state
            stateStore.resetScenario(scenarioId);

            // Broadcast SCENARIO_STARTED
            var firstAgent = scenarioDef.agents().isEmpty() ? null : scenarioDef.agents().get(0).agentId();
            stateStore.updateScenarioStatus(scenarioId, "running", firstAgent);

            // Sequential agent pipeline
            for (var agentDef : scenarioDef.agents()) {
                String agentId = agentDef.agentId();

                // Get agent config
                var agentConfig = configResolver.allAgents().get(agentId);
                if (agentConfig == null) {
                    LOG.errorf("Agent not configured: %s", agentId);
                    stateStore.updateScenarioStatus(scenarioId, "failed", null);
                    return;
                }

                // Broadcast AGENT_STARTED
                long startMs = System.currentTimeMillis();
                stateStore.updateAgentState(scenarioId, agentId, "running", 0L, "OPEN");

                // Setup and dispatch
                String correlationId = UUID.randomUUID().toString();
                String commandContent = buildCommandContent(scenarioDef, agentDef);
                SetupResult result = exampleSetup.setupAndDispatch(
                        scenarioDef.caseId(), tenancyId, agentId,
                        agentConfig.sessionKey(), correlationId, commandContent);

                // Register channels
                stateStore.registerChannel(result.workChannelId(), scenarioId);
                stateStore.registerChannel(result.oversightChannelId(), scenarioId);

                // Poll until terminal
                CommitmentState state = pollUntilTerminal(correlationId);

                // Compute duration
                long durationMs = System.currentTimeMillis() - startMs;

                if (state == null) {
                    // Timeout
                    LOG.warnf("Timeout waiting for agent %s", agentId);
                    stateStore.updateAgentState(scenarioId, agentId, "timeout", durationMs, "EXPIRED");
                    stateStore.updateCommitment(scenarioId, correlationId, agentId, "EXPIRED", "timeout");
                    stateStore.updateScenarioStatus(scenarioId, "failed", null);
                    return;
                }

                // Map CommitmentState to outcome
                String outcome = mapOutcome(state);
                stateStore.updateAgentState(scenarioId, agentId, outcome, durationMs, state.name());
                stateStore.updateCommitment(scenarioId, correlationId, agentId, state.name(), outcome);

                // Non-FULFILLED stops pipeline
                if (state != CommitmentState.FULFILLED) {
                    LOG.infof("Agent %s returned %s — stopping pipeline", agentId, state);
                    stateStore.updateScenarioStatus(scenarioId, "failed", null);
                    return;
                }

                LOG.infof("Agent %s completed in %dms", agentId, durationMs);
            }

            // All agents succeeded
            stateStore.updateScenarioStatus(scenarioId, "completed", null);
            LOG.infof("Scenario '%s' completed successfully", scenarioId);

        } catch (Exception e) {
            LOG.errorf(e, "Scenario execution failed: %s", e.getMessage());
            stateStore.updateScenarioStatus(scenarioId, "failed", null);
        }
    }

    private CommitmentState pollUntilTerminal(String correlationId) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.warnf("Polling interrupted for correlationId=%s", correlationId);
                return null;
            }
            CommitmentState state = examplePoller.checkState(correlationId);
            if (state != null && state.isTerminal()) {
                return state;
            }
        }
        LOG.warnf("Polling timeout (%ds) for correlationId=%s", timeoutSeconds, correlationId);
        return null;
    }

    private String mapOutcome(CommitmentState state) {
        return switch (state) {
            case FULFILLED -> "completed";
            case DECLINED -> "declined";
            case DELEGATED -> "delegated";
            case FAILED -> "failed";
            case EXPIRED -> "timeout";
            default -> state.name().toLowerCase();
        };
    }

    private String buildCommandContent(ScenarioDef scenarioDef, AgentDef agentDef) {
        // Simplified command content — in production this would come from the scenario definition
        return switch (scenarioDef.id()) {
            case "trading-oversight" -> switch (agentDef.agentId()) {
                case "signal" -> "You are the Signal agent. Analyse NVDA market feed.";
                case "risk" -> "You are the Risk agent. Assess: BUY 100 NVDA @ $892.";
                case "execution" -> "You are the Execution agent. Signal: BUY NVDA @ $892. Risk: MEDIUM. Place the order.";
                default -> "Execute task for " + agentDef.role();
            };
            case "multi-agent-dev-team" -> switch (agentDef.agentId()) {
                case "planner" -> "You are the Planner. Review GitHub issue #42.";
                case "coder" -> "You are the Coder. Fix null check in PaymentService.";
                case "reviewer" -> "You are the Reviewer. Review diff for PaymentService.";
                default -> "Execute task for " + agentDef.role();
            };
            case "incident-response" -> switch (agentDef.agentId()) {
                case "investigator" -> "You are the Investigator. P1 alert: payment-service error rate 34% since 02:47 UTC.";
                case "resolver" -> "You are the Resolver. Root cause confirmed: deploy 7f3a2c1 reduced DB pool 20→5. Execute fix.";
                default -> "Execute task for " + agentDef.role();
            };
            default -> "Execute task for " + agentDef.role();
        };
    }
}
