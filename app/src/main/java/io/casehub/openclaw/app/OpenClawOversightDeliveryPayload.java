package io.casehub.openclaw.app;

/**
 * Webhook payload received from OpenClaw when a human responds to an oversight gate
 * question via messaging platform (WhatsApp, Telegram, etc.).
 *
 * <p>Structurally identical to {@link OpenClawDeliveryPayload} today, but kept separate:
 * these represent semantically different events (agent task result vs. human governance
 * decision) and are expected to diverge as oversight responses gain delivery platform
 * metadata (channel, responder identity, timestamp).
 *
 * <p>WARNING: Field names assumed camelCase — verify against live OpenClaw API. See openclaw#11.
 */
public record OpenClawOversightDeliveryPayload(
        String agentId,
        String output
) {}
