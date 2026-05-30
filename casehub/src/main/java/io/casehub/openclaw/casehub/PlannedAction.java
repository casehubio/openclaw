package io.casehub.openclaw.casehub;

import java.util.Map;
import java.util.UUID;

/**
 * Describes a consequential action a worker proposes to take.
 *
 * <p>Fields are intentionally compatible with the {@code ActionRiskClassifier} SPI
 * proposed for casehub-engine-api (casehubio/engine#402). Migration is a pure import
 * swap when that SPI ships.
 *
 * @param workerId    the OpenClaw agentId performing the action
 * @param caseId      the case this action belongs to
 * @param description the agent's output — what it proposes to do (human-readable)
 * @param actionType  structured tag (e.g. "subscription.cancel"); null in Phase 1
 * @param context     domain-specific facts (e.g. amount, target); empty map in Phase 1
 */
public record PlannedAction(
        String workerId,
        UUID caseId,
        String description,
        String actionType,
        Map<String, String> context
) {}
