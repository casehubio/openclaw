package io.casehub.openclaw.client;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class OpenClawHookClient {

    private final OpenClawGatewayClient gatewayClient;
    private final OpenClawClientConfig config;
    private final ConcurrentHashMap<String, OpenClawSession> sessions = new ConcurrentHashMap<>();

    // Single constructor — used by both CDI (@Inject + @RestClient on the gateway parameter)
    // and directly by unit tests (which pass mocks without CDI). Java sees one constructor;
    // CDI and @RestClient are annotation metadata only and do not affect the constructor signature.
    @Inject
    public OpenClawHookClient(@RestClient OpenClawGatewayClient gatewayClient,
                               OpenClawClientConfig config) {
        this.gatewayClient = gatewayClient;
        this.config = config;
    }

    // ── Session registry ─────────────────────────────────────────────────────

    public void registerSession(String agentId, String sessionKey, String webhookUrl) {
        sessions.put(agentId, new OpenClawSession(agentId, sessionKey, webhookUrl));
    }

    public void deregisterSession(String agentId) {
        sessions.remove(agentId);
    }

    public Optional<OpenClawSession> findSession(String agentId) {
        return Optional.ofNullable(sessions.get(agentId));
    }

    // ── Invocation ───────────────────────────────────────────────────────────

    /**
     * Invokes an OpenClaw agent via the hook API using webhook delivery.
     * The agent must have a registered session (agentId → sessionKey + webhookUrl).
     *
     * @param agentId        OpenClaw agent identifier
     * @param message        prompt to deliver to the agent
     * @param model          Claude model to use; null or blank uses the configured default
     * @param timeoutSeconds invocation timeout; 0 uses the configured default
     * @throws OpenClawInvocationException if no session is registered or the gateway returns non-2xx
     */
    public void invoke(String agentId, String message, String model, int timeoutSeconds) {
        OpenClawSession session = sessions.get(agentId);
        if (session == null) {
            throw new OpenClawInvocationException(
                    "No session registered for agentId: " + agentId);
        }

        String effectiveModel = (model == null || model.isBlank())
                ? config.agent().defaultModel()
                : model;

        int effectiveTimeout = (timeoutSeconds > 0)
                ? timeoutSeconds
                : config.agent().defaultTimeoutSeconds();

        AgentInvocationRequest request = AgentInvocationRequest.forWebhook(
                message, agentId, session.webhookUrl(),
                effectiveModel, effectiveTimeout, session.sessionKey(), null);

        Response response = gatewayClient.invokeAgent(request);
        // Response does not implement AutoCloseable — use try-finally for explicit close
        try {
            if (response.getStatus() / 100 != 2) {
                throw new OpenClawInvocationException(
                        "OpenClaw /hooks/agent returned HTTP " + response.getStatus()
                        + " for agentId: " + agentId);
            }
        } finally {
            response.close();
        }
    }

    /**
     * Wakes an OpenClaw agent with a lightweight message event. Does not require a
     * registered session — wake is used for heartbeat-initiated flows, not in-case steps.
     *
     * @param agentId OpenClaw agent identifier
     * @param message wake message to deliver
     * @throws OpenClawInvocationException if the gateway returns non-2xx
     */
    public void wake(String agentId, String message) {
        Response response = gatewayClient.wakeAgent(new AgentWakeRequest(agentId, message));
        // Response does not implement AutoCloseable — use try-finally for explicit close
        try {
            if (response.getStatus() / 100 != 2) {
                throw new OpenClawInvocationException(
                        "OpenClaw /hooks/wake returned HTTP " + response.getStatus()
                        + " for agentId: " + agentId);
            }
        } finally {
            response.close();
        }
    }
}
