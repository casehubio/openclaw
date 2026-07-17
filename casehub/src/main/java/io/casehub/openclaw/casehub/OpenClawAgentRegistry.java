package io.casehub.openclaw.casehub;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Routing maps for OpenClaw agent sessions. Shared by WorkerProvisioner (write),
 * ChannelBackend (read for routing), and WorkerStatusListener (cleanup).
 *
 * <p>Supports 1:N agents per case — multiple agents can be registered for the same
 * caseId simultaneously (e.g. casehub-life's domain-specialised agents).
 */
@ApplicationScoped
public class OpenClawAgentRegistry {

    private static final Logger log = Logger.getLogger(OpenClawAgentRegistry.class);

    private final ConcurrentHashMap<String, UUID>      agentToCase       = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Set<String>> caseToAgents      = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String>    agentToSessionKey = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String>      caseToTenancy     = new ConcurrentHashMap<>();

    public record DeregistrationResult(UUID caseId, boolean wasLastAgent) {}

    public void register(String agentId, String tenancyId, UUID caseId, String sessionKey) {
        UUID previousCase = agentToCase.put(agentId, caseId);
        if (previousCase != null && !previousCase.equals(caseId)) {
            Set<String> remaining = caseToAgents.computeIfPresent(previousCase, (k, agents) -> {
                agents.remove(agentId);
                return agents.isEmpty() ? null : agents;
            });
            if (remaining == null) {
                caseToTenancy.remove(previousCase);
            }
        }
        caseToAgents.computeIfAbsent(caseId, k -> ConcurrentHashMap.newKeySet()).add(agentId);
        agentToSessionKey.put(agentId, sessionKey);
        caseToTenancy.put(caseId, tenancyId);
    }

    public DeregistrationResult deregister(String agentId) {
        UUID    caseId       = agentToCase.remove(agentId);
        boolean wasLastAgent = false;
        if (caseId != null) {
            Set<String> remaining = caseToAgents.computeIfPresent(caseId, (k, agents) -> {
                agents.remove(agentId);
                return agents.isEmpty() ? null : agents;
            });
            wasLastAgent = (remaining == null);
            if (wasLastAgent) {
                caseToTenancy.remove(caseId);
            }
        }
        agentToSessionKey.remove(agentId);
        return new DeregistrationResult(caseId, wasLastAgent);
    }

    /**
     * Transitional — prefer {@link #findAgentIds(UUID)} for new callers.
     * Returns any single agent from the set. Logs a warning when multiple agents
     * are registered (signals that parallel routing via openclaw#70 is needed).
     */
    public Optional<String> findAgentId(UUID caseId) {
        Set<String> agents = caseToAgents.get(caseId);
        if (agents == null || agents.isEmpty()) {
            return Optional.empty();
        }
        if (agents.size() > 1) {
            log.warnf("Multiple agents registered for caseId=%s: %s — returning arbitrary agent. " +
                      "Parallel COMMAND routing needed (openclaw#70).", caseId, agents);
        }
        return agents.stream().findFirst();
    }

    public Set<String> findAgentIds(UUID caseId) {
        Set<String> agents = caseToAgents.get(caseId);
        return agents != null ? Set.copyOf(agents) : Set.of();
    }

    public Optional<UUID> findCaseId(String agentId) {
        return Optional.ofNullable(agentToCase.get(agentId));
    }

    public Optional<String> findSessionKey(String agentId) {
        return Optional.ofNullable(agentToSessionKey.get(agentId));
    }

    public Optional<String> findTenancyId(UUID caseId) {
        return Optional.ofNullable(caseToTenancy.get(caseId));
    }

    public boolean hasAgentsForCase(UUID caseId) {
        return caseToAgents.containsKey(caseId);
    }
}
