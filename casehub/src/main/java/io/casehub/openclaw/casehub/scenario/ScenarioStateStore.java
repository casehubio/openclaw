package io.casehub.openclaw.casehub.scenario;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

/**
 * In-memory state store for demo scenarios.
 * <p>
 * Maintains current state for agents, messages, commitments, gates, and scenario status.
 * Broadcasts state changes as typed CaseExecutionEvent objects to registered listeners.
 * <p>
 * Thread-safe: all state maps are concurrent collections, all broadcast operations
 * iterate listeners with per-listener try/catch to prevent propagation.
 */
@ApplicationScoped
public class ScenarioStateStore {

    private static final Logger LOG = Logger.getLogger(ScenarioStateStore.class);
    private static final int MAX_RECENT_MESSAGES = 100;

    private final ScenarioMetadataProvider metadata;

    // State maps — all concurrent for thread safety
    private final Map<String, String> scenarioStatuses = new ConcurrentHashMap<>(); // scenarioId → "idle"|"running"|"completed"|"failed"
    private final Map<String, Map<String, AgentState>> agentStates = new ConcurrentHashMap<>(); // scenarioId → agentId → AgentState
    private final Map<String, List<CaseExecutionEvent.ChannelMessageEvent>> messages = new ConcurrentHashMap<>(); // scenarioId → messages
    private final Map<String, Map<String, CaseExecutionEvent.CommitmentUpdatedEvent>> commitments = new ConcurrentHashMap<>(); // scenarioId → commitmentId → event
    private final Map<String, GateState> pendingGates = new ConcurrentHashMap<>(); // gateId → GateState
    private final Map<String, String> gateToScenario = new ConcurrentHashMap<>(); // gateId → scenarioId

    // Channel → scenario lookup
    private final Map<UUID, String> channelToScenario = new ConcurrentHashMap<>();

    // Listeners
    private final Set<ScenarioEventListener> listeners = new CopyOnWriteArraySet<>();

    @Inject
    public ScenarioStateStore(ScenarioMetadataProvider metadata) {
        this.metadata = metadata;
    }

    public void addListener(ScenarioEventListener listener) {
        listeners.add(listener);
    }

    public void removeListener(ScenarioEventListener listener) {
        listeners.remove(listener);
    }

    public boolean isRunning(String scenarioId) {
        var status = scenarioStatuses.get(scenarioId);
        return "running".equals(status) || "starting".equals(status);
    }

    public boolean tryStart(String scenarioId) {
        return scenarioStatuses.putIfAbsent(scenarioId, "starting") == null;
    }

    public void registerChannel(UUID channelId, String scenarioId) {
        channelToScenario.put(channelId, scenarioId);
    }

    public Optional<String> scenarioForChannel(UUID channelId) {
        return Optional.ofNullable(channelToScenario.get(channelId));
    }

    public void updateAgentState(String scenarioId, String agentId, String state,
                                  long durationMs, String commitmentState) {
        var scenarioDef = metadata.allScenarios().get(scenarioId);
        if (scenarioDef == null) {
            LOG.warnf("updateAgentState called for unknown scenario: %s", scenarioId);
            return;
        }

        var agentDef = scenarioDef.agents().stream()
                .filter(a -> a.agentId().equals(agentId))
                .findFirst()
                .orElse(null);

        if (agentDef == null) {
            LOG.warnf("updateAgentState called for unknown agent: %s in scenario %s", agentId, scenarioId);
            return;
        }

        // Update internal state
        var agentState = new AgentState(agentId, agentDef.role(), state, durationMs);
        agentStates.computeIfAbsent(scenarioId, k -> new ConcurrentHashMap<>())
                .put(agentId, agentState);

        // Broadcast appropriate event
        var now = Instant.now();
        if ("running".equals(state)) {
            broadcast(new CaseExecutionEvent.AgentStartedEvent(scenarioId, now, agentId, agentDef.role()));
        } else {
            // Terminal states: completed, failed, declined, delegated, timeout
            broadcast(new CaseExecutionEvent.AgentCompletedEvent(scenarioId, now, agentId, agentDef.role(), state, durationMs));
        }
    }

    public void addMessage(String scenarioId, String agentId, String role, String content) {
        var event = new CaseExecutionEvent.ChannelMessageEvent(scenarioId, Instant.now(), agentId, role, content);

        var msgList = messages.computeIfAbsent(scenarioId, k -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (msgList) {
            msgList.add(event);
            int overage = msgList.size() - MAX_RECENT_MESSAGES;
            if (overage > 0) {
                msgList.subList(0, overage).clear();
            }
        }

        broadcast(event);
    }

    public void updateCommitment(String scenarioId, String commitmentId, String agentId,
                                  String state, String outcome) {
        var event = new CaseExecutionEvent.CommitmentUpdatedEvent(
                scenarioId, Instant.now(), agentId, commitmentId, state, outcome);

        commitments.computeIfAbsent(scenarioId, k -> new ConcurrentHashMap<>())
                .put(commitmentId, event);

        broadcast(event);
    }

    public void updateScenarioStatus(String scenarioId, String status, String activeAgent) {
        var scenarioDef = metadata.allScenarios().get(scenarioId);
        if (scenarioDef == null) {
            LOG.warnf("updateScenarioStatus called for unknown scenario: %s", scenarioId);
            return;
        }

        scenarioStatuses.put(scenarioId, status);

        var now = Instant.now();
        CaseExecutionEvent event = switch (status) {
            case "running" -> new CaseExecutionEvent.ScenarioStartedEvent(scenarioId, now);
            case "completed" -> new CaseExecutionEvent.ScenarioCompletedEvent(scenarioId, now);
            case "failed" -> new CaseExecutionEvent.ScenarioFailedEvent(scenarioId, now, "");
            default -> {
                LOG.warnf("Unexpected scenario status: %s", status);
                yield null;
            }
        };

        if (event != null) {
            broadcast(event);
        }
    }

    public void fireGatePending(String scenarioId, String gateId, String agentId,
                                String action, String classification, String priorAgentsJson) {
        var gateState = new GateState(gateId, agentId, action, classification, priorAgentsJson);
        pendingGates.put(gateId, gateState);
        gateToScenario.put(gateId, scenarioId);

        var event = new CaseExecutionEvent.GatePendingEvent(
                scenarioId, Instant.now(), gateId, agentId, action, classification, priorAgentsJson);
        broadcast(event);
    }

    public void fireGateResolved(String scenarioId, String gateId, String decision) {
        pendingGates.remove(gateId);
        gateToScenario.remove(gateId);

        var event = new CaseExecutionEvent.GateResolvedEvent(scenarioId, Instant.now(), gateId, decision);
        broadcast(event);
    }

    public ScenarioStateSnapshot currentState(String scenarioId) {
        var status = scenarioStatuses.getOrDefault(scenarioId, "idle");

        var agents = agentStates.getOrDefault(scenarioId, Map.of()).values().stream()
                .toList();

        var recentMessages = messages.getOrDefault(scenarioId, List.of()).stream()
                .toList();

        // Find pending gate for this scenario
        GateState pendingGate = gateToScenario.entrySet().stream()
                .filter(e -> e.getValue().equals(scenarioId))
                .map(e -> pendingGates.get(e.getKey()))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);

        return new ScenarioStateSnapshot(scenarioId, status, agents, pendingGate, recentMessages);
    }

    public List<ScenarioStateSnapshot> listScenarioSummaries() {
        return metadata.allScenarios().values().stream()
                .map(scenarioDef -> currentState(scenarioDef.id()))
                .toList();
    }

    public void resetScenario(String scenarioId) {
        // Clear per-scenario state
        agentStates.remove(scenarioId);
        messages.remove(scenarioId);
        commitments.remove(scenarioId);
        scenarioStatuses.remove(scenarioId);

        // Clear gates for this scenario
        var gatesToRemove = gateToScenario.entrySet().stream()
                .filter(e -> e.getValue().equals(scenarioId))
                .map(Map.Entry::getKey)
                .toList();

        gatesToRemove.forEach(gateId -> {
            pendingGates.remove(gateId);
            gateToScenario.remove(gateId);
        });
    }

    private void broadcast(CaseExecutionEvent event) {
        for (var listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (Exception e) {
                LOG.errorf(e, "Listener threw exception on event: %s", e.getMessage());
            }
        }
    }
}
