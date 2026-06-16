package io.casehub.openclaw.app.example;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.casehub.api.spi.PlannedAction;
import io.casehub.api.spi.RiskDecision;

import static org.assertj.core.api.Assertions.assertThat;

class DemoGateClassifierTest {

    @Test
    void emptyAgentId_returnsAutonomous() {
        final var classifier = new DemoGateClassifier("");
        assertThat(classifier.classify(action("execution"))).isInstanceOf(RiskDecision.Autonomous.class);
    }

    @Test
    void blankAgentId_returnsAutonomous() {
        final var classifier = new DemoGateClassifier("   ");
        assertThat(classifier.classify(action("execution"))).isInstanceOf(RiskDecision.Autonomous.class);
    }

    @Test
    void matchingAgentId_returnsGateRequired() {
        final var classifier = new DemoGateClassifier("execution");
        assertThat(classifier.classify(action("execution"))).isInstanceOf(RiskDecision.GateRequired.class);
    }

    @Test
    void matchingAgentId_caseInsensitive_returnsGateRequired() {
        final var classifier = new DemoGateClassifier("Execution");
        assertThat(classifier.classify(action("execution"))).isInstanceOf(RiskDecision.GateRequired.class);
    }

    @Test
    void nonMatchingAgentId_returnsAutonomous() {
        final var classifier = new DemoGateClassifier("execution");
        assertThat(classifier.classify(action("signal"))).isInstanceOf(RiskDecision.Autonomous.class);
    }

    @Test
    void nullWorkerId_returnsAutonomous() {
        final var classifier = new DemoGateClassifier("execution");
        final var action = new PlannedAction(null, UUID.randomUUID(), "outcome", "COMPLETION", Map.of());
        assertThat(classifier.classify(action)).isInstanceOf(RiskDecision.Autonomous.class);
    }

    @Test
    void gateRequired_reasonContainsAgentId() {
        final var classifier = new DemoGateClassifier("reviewer");
        final var result = (RiskDecision.GateRequired) classifier.classify(action("reviewer"));
        assertThat(result.reason()).contains("reviewer");
    }

    private static PlannedAction action(final String agentId) {
        return new PlannedAction(agentId, UUID.randomUUID(), "some outcome", "COMPLETION", Map.of());
    }
}
