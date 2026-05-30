package io.casehub.openclaw.app;

import com.fasterxml.jackson.annotation.JsonAlias;

/**
 * Webhook payload received from OpenClaw when a human responds to an oversight gate
 * question via messaging platform (WhatsApp, Telegram, etc.).
 *
 * <p>Structurally identical to {@link OpenClawDeliveryPayload} today, but kept separate:
 * these represent semantically different events (agent task result vs. human governance
 * decision) and are expected to diverge as oversight responses gain delivery platform
 * metadata (channel, responder identity, timestamp).
 *
 * <p>Accepts both camelCase and snake_case field names via {@code @JsonAlias} — defensive
 * until field names are verified against a live OpenClaw instance (openclaw#11).
 */
public record OpenClawOversightDeliveryPayload(
        @JsonAlias("agent_id") String agentId,
        @JsonAlias({"result", "content"}) String output
) {}
