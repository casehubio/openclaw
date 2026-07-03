package io.casehub.openclaw.casehub.scenario;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ScenarioMetadataProviderTest {

    final ScenarioMetadataProvider provider = new ScenarioMetadataProvider();

    @Test
    void allScenarios_returnsThreeScenarios() {
        var scenarios = provider.allScenarios();
        assertThat(scenarios).hasSize(3)
                .containsKeys("trading-oversight", "multi-agent-dev-team", "incident-response");
    }

    @Test
    void tradingOversight_hasCorrectAgents() {
        var def = provider.allScenarios().get("trading-oversight");
        assertThat(def.agents()).extracting(AgentDef::agentId)
                .containsExactly("signal", "risk", "execution");
        assertThat(def.gateAgentId()).isEqualTo("execution");
    }

    @Test
    void scenarioForCaseId_resolvesCorrectly() {
        var scenarios = provider.allScenarios();
        for (var entry : scenarios.entrySet()) {
            assertThat(provider.scenarioForCaseId(entry.getValue().caseId()))
                    .isPresent()
                    .get().extracting(ScenarioDef::id).isEqualTo(entry.getKey());
        }
    }

    @Test
    void scenarioForCaseId_unknownId_empty() {
        assertThat(provider.scenarioForCaseId(java.util.UUID.randomUUID())).isEmpty();
    }

    @Test
    void agentSteps_areSequentialStartingAtOne() {
        provider.allScenarios().values().forEach(def -> {
            for (int i = 0; i < def.agents().size(); i++) {
                assertThat(def.agents().get(i).step()).isEqualTo(i + 1);
            }
        });
    }
}
