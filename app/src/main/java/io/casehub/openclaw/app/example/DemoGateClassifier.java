package io.casehub.openclaw.app.example;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.casehub.api.spi.ActionRiskClassifier;
import io.casehub.api.spi.ClassificationContext;
import io.casehub.api.spi.RiskClassifier;
import io.casehub.api.spi.RiskDecision;
import io.casehub.worker.api.PlannedAction;

/**
 * Demo-only ActionRiskClassifier. Gates when action.workerId() matches the configured
 * agentId (case-insensitive). Completely inert when casehub.example.gate.agentid is
 * absent or blank — returns Autonomous for every action. Always registered as a CDI bean.
 *
 * <p>Configured per-example via CASEHUB_EXAMPLE_GATE_AGENTID env var (single underscore;
 * casehub.example.gate.agentid has no hyphens so no double-underscore needed in env vars).
 *
 * <p>Uses Optional<String> to handle the absent case cleanly — SmallRye Config treats
 * empty string values as null for non-Optional String fields and rejects them at startup.
 *
 * <p>Follows gate-fail-open-asymmetry protocol: exceptions propagate to
 * OversightGateService.classifyMostRestrictive() which applies the GateRequired fail-safe.
 */
@ApplicationScoped
@RiskClassifier
class DemoGateClassifier implements ActionRiskClassifier {

    @ConfigProperty(name = "casehub.example.gate.agentid")
    Optional<String> gateAgentId;

    @Inject
    DemoGateClassifier() {}

    /** Test constructor — converts String to Optional, treating blank as absent. */
    DemoGateClassifier(final String gateAgentId) {
        this.gateAgentId = Optional.ofNullable(gateAgentId).filter(s -> !s.isBlank());
    }

    @Override
    public RiskDecision classify(final PlannedAction action, final ClassificationContext context) {
        if (gateAgentId.isEmpty()) {
            return new RiskDecision.Autonomous();
        }
        if (context.workerId() == null) {
            return new RiskDecision.Autonomous();
        }
        if (!gateAgentId.get().equalsIgnoreCase(context.workerId())) {
            return new RiskDecision.Autonomous();
        }
        return new RiskDecision.GateRequired(
                "Demo gate — agent '" + context.workerId() + "' requires oversight approval",
                true, null, null, null, null);
    }
}
