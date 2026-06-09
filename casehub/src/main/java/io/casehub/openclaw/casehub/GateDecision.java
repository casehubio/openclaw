package io.casehub.openclaw.casehub;

import java.util.UUID;

public sealed interface GateDecision permits GateDecision.Autonomous, GateDecision.GatePending {
    record Autonomous() implements GateDecision {}
    record GatePending(UUID gateId, String reason) implements GateDecision {}
}
