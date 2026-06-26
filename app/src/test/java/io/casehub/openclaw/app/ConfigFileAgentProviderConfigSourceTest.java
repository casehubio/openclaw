package io.casehub.openclaw.app;

import io.casehub.openclaw.casehub.AgentProviderConfigSource;
import io.casehub.openclaw.casehub.AgentProviderConfigSource.AgentConfig;
import io.casehub.openclaw.casehub.OpenClawCasehubConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigFileAgentProviderConfigSourceTest {

    @Test
    void allAgents_convertsConfigToAgentConfigMap() {
        OpenClawCasehubConfig config = buildConfig(Map.of(
                "finance-agent", entry(List.of("finance", "banking"), "fin-key"),
                "code-agent", entry(List.of("code-review"), "cr-key")));

        AgentProviderConfigSource source = new ConfigFileAgentProviderConfigSource(config);
        Map<String, AgentConfig> agents = source.allAgents();

        assertThat(agents).hasSize(2);
        assertThat(agents.get("finance-agent").sessionKey()).isEqualTo("fin-key");
        assertThat(agents.get("finance-agent").capabilities()).containsExactlyInAnyOrder("finance", "banking");
        assertThat(agents.get("code-agent").sessionKey()).isEqualTo("cr-key");
        assertThat(agents.get("code-agent").capabilities()).containsExactly("code-review");
    }

    @Test
    void allAgents_emptyConfig_returnsEmptyMap() {
        OpenClawCasehubConfig config = buildConfig(Map.of());
        AgentProviderConfigSource source = new ConfigFileAgentProviderConfigSource(config);
        assertThat(source.allAgents()).isEmpty();
    }

    private OpenClawCasehubConfig buildConfig(Map<String, OpenClawCasehubConfig.AgentEntry> agents) {
        return new OpenClawCasehubConfig() {
            @Override public Map<String, AgentEntry> agents() { return agents; }
            @Override public Oversight oversight() { return Optional::empty; }
        };
    }

    private OpenClawCasehubConfig.AgentEntry entry(List<String> caps, String sk) {
        return new OpenClawCasehubConfig.AgentEntry() {
            @Override public List<String> capabilities() { return caps; }
            @Override public String sessionKey() { return sk; }
        };
    }
}
