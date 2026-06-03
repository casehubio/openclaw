package io.casehub.openclaw.app;

import com.fasterxml.jackson.annotation.JsonAlias;

/**
 * Webhook payload received from OpenClaw when an agent completes via deliver:webhook.
 *
 * <p>{@code @JsonAlias} is a permanent design choice (openclaw#11): OpenClaw's request API
 * uses camelCase ({@code agentId}, {@code timeoutSeconds}), making camelCase the expected
 * delivery format, but the aliases guard against any divergence without imposing a runtime
 * cost. Unknown fields (e.g. {@code status}) are silently ignored — Quarkus disables
 * FAIL_ON_UNKNOWN_PROPERTIES by default.
 */
public record OpenClawDeliveryPayload(
    @JsonAlias("agent_id") String agentId,
    @JsonAlias({"result", "content"}) String output
) {}
