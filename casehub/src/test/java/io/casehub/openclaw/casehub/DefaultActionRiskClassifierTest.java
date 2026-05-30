package io.casehub.openclaw.casehub;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DefaultActionRiskClassifierTest {

    DefaultActionRiskClassifier classifier = new DefaultActionRiskClassifier();

    @Test
    void classify_anyAction_returnsAutonomous() {
        PlannedAction action = new PlannedAction("finance-agent", UUID.randomUUID(),
                "cancel subscription", "subscription.cancel", Map.of());
        assertThat(classifier.classify(action)).isInstanceOf(RiskDecision.Autonomous.class);
    }

    @Test
    void classify_nullFields_returnsAutonomous() {
        PlannedAction action = new PlannedAction("agent", UUID.randomUUID(), "desc", null, Map.of());
        assertThat(classifier.classify(action)).isInstanceOf(RiskDecision.Autonomous.class);
    }
}
