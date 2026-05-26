package io.casehub.openclaw.client;

import com.fasterxml.jackson.annotation.JsonInclude;

public record AgentInvocationRequest(
        String message,
        String agentId,
        String deliver,
        String to,
        String model,
        int timeoutSeconds,
        // @JsonInclude(NON_NULL) is placed on the record component declaration.
        // Jackson maps it from the component to the serialised property when processing the record.
        // Correctness is verified by OpenClawGatewayClientIT#invokeAgent_nullSessionKey_sessionNameOmittedFromJson.
        @JsonInclude(JsonInclude.Include.NON_NULL) String sessionName,
        @JsonInclude(JsonInclude.Include.NON_NULL) String wakeMode
) {
    /**
     * Factory for the only delivery mode casehub-openclaw uses. Callers must not
     * construct AgentInvocationRequest directly — use this factory to prevent
     * accidental use of a different deliver value. Package-private visibility
     * enforces this: callers outside this package must go through
     * {@link io.casehub.openclaw.client.OpenClawHookClient#invoke(String, String, String, int)}.
     *
     * sessionName: maps to OpenClaw's session_name (Python SDK). JSON field name is
     * "sessionName" (camelCase), consistent with other OpenClaw fields (agentId,
     * timeoutSeconds). If OpenClaw's HTTP API uses snake_case instead, add
     * @JsonProperty("session_name") to the component — to be verified against the
     * live API before casehub/ SPI implementations are built.
     *
     * wakeMode: how the agent is woken. Null uses OpenClaw's default (appropriate for
     * the direct-call pattern). Values are undocumented in the current spec — pass
     * null until verified. If OpenClaw documents required values for webhook delivery
     * mode, update accordingly.
     *
     * /hooks/wake body format: {agentId, message} is assumed based on "lightweight
     * nudge — wakes agent with a text event" in the spec. No body schema is specified.
     * Verify against the live API before relying on wake() in production.
     */
    static AgentInvocationRequest forWebhook(
            String message,
            String agentId,
            String to,
            String model,
            int timeoutSeconds,
            String sessionName,
            String wakeMode) {
        return new AgentInvocationRequest(
                message, agentId, "webhook", to, model, timeoutSeconds, sessionName, wakeMode);
    }
}
