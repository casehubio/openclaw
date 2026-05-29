package io.casehub.openclaw.app;

/**
 * Webhook payload received from OpenClaw when an agent completes via deliver:webhook.
 *
 * WARNING: Field names assumed camelCase based on other OpenClaw API fields (agentId, timeoutSeconds).
 * Verify against live OpenClaw API before production use — see openclaw#11.
 * If OpenClaw uses snake_case, add @JsonProperty("agent_id") and @JsonProperty("output").
 */
public record OpenClawDeliveryPayload(
    String agentId,
    String output
) {}
