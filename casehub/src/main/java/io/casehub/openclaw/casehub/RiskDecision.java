package io.casehub.openclaw.casehub;

public sealed interface RiskDecision permits RiskDecision.Autonomous, RiskDecision.GateRequired {
    record Autonomous() implements RiskDecision {}
    record GateRequired(String reason, boolean reversible) implements RiskDecision {}
}
