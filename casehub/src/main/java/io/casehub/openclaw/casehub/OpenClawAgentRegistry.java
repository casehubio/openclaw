package io.casehub.openclaw.casehub;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Routing maps for OpenClaw agent sessions. Shared by WorkerProvisioner (write),
 * ChannelBackend (read for routing), and WorkerStatusListener (cleanup).
 *
 * <p>MVP constraint: 1:1 caseId ↔ agentId. A second register() call for the same
 * caseId silently overwrites the previous entry. Log a warning at register() time
 * if this occurs — it indicates a misconfigured or unexpected provisioning pattern.
 */
@ApplicationScoped
public class OpenClawAgentRegistry {

    private final ConcurrentHashMap<String, UUID> agentToCase = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> caseToAgent = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> agentToSessionKey = new ConcurrentHashMap<>();

    public void register(String agentId, UUID caseId, String sessionKey) {
        agentToCase.put(agentId, caseId);
        caseToAgent.put(caseId, agentId);
        agentToSessionKey.put(agentId, sessionKey);
    }

    public void deregister(String agentId) {
        UUID caseId = agentToCase.remove(agentId);
        if (caseId != null) caseToAgent.remove(caseId);
        agentToSessionKey.remove(agentId);
    }

    public Optional<String> findAgentId(UUID caseId) {
        return Optional.ofNullable(caseToAgent.get(caseId));
    }

    public Optional<String> findSessionKey(String agentId) {
        return Optional.ofNullable(agentToSessionKey.get(agentId));
    }
}
