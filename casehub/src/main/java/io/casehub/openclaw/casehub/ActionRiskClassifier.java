package io.casehub.openclaw.casehub;

/**
 * Classifies the risk of a proposed worker action, deciding whether autonomous
 * execution or a human oversight gate is required.
 *
 * <p><b>Phase 1 ({@link DefaultActionRiskClassifier}):</b> always {@link RiskDecision.Autonomous}.
 * No risk rules are configured — all actions proceed without oversight.
 *
 * <p>This is a local placeholder for the {@code ActionRiskClassifier} SPI proposed for
 * {@code casehub-engine-api} (casehubio/engine#402). The local contract has been verified
 * identical to the engine#402 proposal as of 2026-06-04: same method signature, same type
 * names, same {@code @Alternative @Priority(1)} override pattern. When engine#402 ships,
 * migration is a pure import swap — no code changes beyond the import statement.
 *
 * <p>Override the default bean with {@code @Alternative @Priority(1)}.
 */
public interface ActionRiskClassifier {
    RiskDecision classify(PlannedAction action);
}
