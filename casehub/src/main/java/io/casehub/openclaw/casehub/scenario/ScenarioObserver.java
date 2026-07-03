package io.casehub.openclaw.casehub.scenario;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.casehub.qhorus.api.gateway.MessageObserver;
import io.casehub.qhorus.api.gateway.MessageReceivedEvent;
import io.casehub.qhorus.api.message.MessageType;

/**
 * Feeds the ScenarioStateStore from Qhorus message dispatches for demo scenarios.
 *
 * <p>EVENT messages are excluded — {@link io.casehub.qhorus.api.message.MessageType#isAgentVisible()}
 * returns false for EVENT; their content is null.
 *
 * <p>Only processes messages from channels registered to demo scenarios. Unknown channels
 * are silently ignored.
 *
 * <p>Per the MessageObserver SPI contract: never propagate exceptions. All state updates
 * are wrapped in try/catch and logged on failure.
 */
@ApplicationScoped
public class ScenarioObserver implements MessageObserver {

    private static final Logger log = Logger.getLogger(ScenarioObserver.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final ScenarioStateStore stateStore;
    private final ScenarioMetadataProvider metadata;

    @Inject
    ScenarioObserver(ScenarioStateStore stateStore, ScenarioMetadataProvider metadata) {
        this.stateStore = stateStore;
        this.metadata = metadata;
    }

    @Override
    public void onMessage(MessageReceivedEvent event) {
        if (!event.messageType().isAgentVisible()) return;

        var scenarioOpt = stateStore.scenarioForChannel(event.channelId());
        if (scenarioOpt.isEmpty()) return;

        String scenarioId = scenarioOpt.get();
        try {
            var scenario = metadata.allScenarios().get(scenarioId);
            boolean isGateAgent = scenario != null
                    && scenario.gateAgentId() != null
                    && scenario.gateAgentId().equals(event.senderId());

            if (isGateAgent && event.messageType() == MessageType.COMMAND) {
                handleGatePending(scenarioId, event);
            } else if (isGateAgent && (event.messageType() == MessageType.RESPONSE
                    || event.messageType() == MessageType.DECLINE)) {
                handleGateResolved(scenarioId, event);
            } else {
                String role = resolveRole(scenarioId, event.senderId());
                stateStore.addMessage(scenarioId, event.senderId(), role, event.content());
            }
        } catch (Exception e) {
            log.errorf(e, "ScenarioObserver failed for channel %s — ignoring", event.channelName());
        }
    }

    private String resolveRole(String scenarioId, String senderId) {
        // Best-effort role lookup from scenario metadata
        var scenario = metadata.allScenarios().get(scenarioId);
        if (scenario == null) return senderId;

        return scenario.agents().stream()
                .filter(a -> a.agentId().equals(senderId))
                .findFirst()
                .map(AgentDef::role)
                .orElse(senderId);
    }

    private void handleGatePending(String scenarioId, MessageReceivedEvent event) {
        // Parse JSON content to extract action, classification, priorAgents
        String action = "";
        String classification = "";
        String priorAgents = "";

        try {
            JsonNode node = mapper.readTree(event.content());
            action = node.path("action").asText("");
            classification = node.path("classification").asText("");
            priorAgents = node.path("priorAgents").asText("");
        } catch (Exception e) {
            // Malformed JSON — fall back to treating whole content as action
            log.warnf("Failed to parse gate COMMAND JSON, using content as action: %s", e.getMessage());
            action = event.content();
        }

        stateStore.fireGatePending(scenarioId, event.correlationId(), event.senderId(),
                action, classification, priorAgents);
    }

    private void handleGateResolved(String scenarioId, MessageReceivedEvent event) {
        stateStore.fireGateResolved(scenarioId, event.correlationId(), event.content());
    }
}
