package io.casehub.openclaw.casehub;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class DefaultActionRiskClassifier implements ActionRiskClassifier {

    @Override
    public RiskDecision classify(PlannedAction action) {
        return new RiskDecision.Autonomous();
    }
}
