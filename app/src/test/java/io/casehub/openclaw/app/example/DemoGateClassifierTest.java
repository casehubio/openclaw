package io.casehub.openclaw.app.example;

import org.junit.jupiter.api.Test;

import io.casehub.api.spi.ClassificationContext;
import io.casehub.api.spi.RiskDecision;
import io.casehub.worker.api.PlannedAction;

import static org.assertj.core.api.Assertions.assertThat;

class DemoGateClassifierTest {

    private static final PlannedAction ACTION = PlannedAction.of("some outcome", "COMPLETION");

    private static ClassificationContext ctx(String workerId) {
        return new ClassificationContext(workerId, null, null, null, null, null);
    }

    @Test
    void emptyAgentId_returnsAutonomous() {
        final var classifier = new DemoGateClassifier("");
        assertThat(classifier.classify(ACTION, ctx("execution"))).isInstanceOf(RiskDecision.Autonomous.class);
    }

    @Test
    void blankAgentId_returnsAutonomous() {
        final var classifier = new DemoGateClassifier("   ");
        assertThat(classifier.classify(ACTION, ctx("execution"))).isInstanceOf(RiskDecision.Autonomous.class);
    }

    @Test
    void matchingAgentId_returnsGateRequired() {
        final var classifier = new DemoGateClassifier("execution");
        assertThat(classifier.classify(ACTION, ctx("execution"))).isInstanceOf(RiskDecision.GateRequired.class);
    }

    @Test
    void matchingAgentId_caseInsensitive_returnsGateRequired() {
        final var classifier = new DemoGateClassifier("Execution");
        assertThat(classifier.classify(ACTION, ctx("execution"))).isInstanceOf(RiskDecision.GateRequired.class);
    }

    @Test
    void nonMatchingAgentId_returnsAutonomous() {
        final var classifier = new DemoGateClassifier("execution");
        assertThat(classifier.classify(ACTION, ctx("signal"))).isInstanceOf(RiskDecision.Autonomous.class);
    }

    @Test
    void nullWorkerId_returnsAutonomous() {
        final var classifier = new DemoGateClassifier("execution");
        assertThat(classifier.classify(ACTION, ctx(null))).isInstanceOf(RiskDecision.Autonomous.class);
    }

    @Test
    void gateRequired_reasonContainsAgentId() {
        final var classifier = new DemoGateClassifier("reviewer");
        final var result = (RiskDecision.GateRequired) classifier.classify(ACTION, ctx("reviewer"));
        assertThat(result.reason()).contains("reviewer");
    }
}
