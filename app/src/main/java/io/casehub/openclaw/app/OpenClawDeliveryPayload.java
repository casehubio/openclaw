package io.casehub.openclaw.app;

import com.fasterxml.jackson.annotation.JsonAlias;

/**
 * Webhook payload received from OpenClaw when an agent completes via deliver:webhook.
 *
 * <p>Accepts both camelCase and snake_case field names via {@code @JsonAlias} — defensive
 * until field names are verified against a live OpenClaw instance (openclaw#11).
 * Other likely aliases for {@code output}: {@code result}, {@code content}.
 */
public record OpenClawDeliveryPayload(
    @JsonAlias("agent_id") String agentId,
    @JsonAlias({"result", "content"}) String output
) {}
