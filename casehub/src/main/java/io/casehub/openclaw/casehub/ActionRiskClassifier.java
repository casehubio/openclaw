package io.casehub.openclaw.casehub;

/**
 * Classifies the risk of a proposed worker action, deciding whether autonomous
 * execution or a human oversight gate is required.
 *
 * <p><b>Phase 1 ({@link DefaultActionRiskClassifier}):</b> always {@link RiskDecision.Autonomous}.
 * No risk rules are configured — all actions proceed without oversight.
 *
 * <p>This is a local placeholder for the {@code ActionRiskClassifier} SPI proposed for
 * {@code casehub-engine-api} (casehubio/engine#402). When that SPI ships, replace this
 * interface and its implementations with the engine-api import — the contract is
 * identical by design.
 *
 * <p>Override the default bean with {@code @Alternative @Priority(1)}.
 */
public interface ActionRiskClassifier {
    RiskDecision classify(PlannedAction action);
}
