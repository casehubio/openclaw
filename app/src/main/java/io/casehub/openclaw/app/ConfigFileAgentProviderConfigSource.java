package io.casehub.openclaw.app;

import io.casehub.openclaw.casehub.AgentProviderConfigSource;
import io.casehub.openclaw.casehub.OpenClawCasehubConfig;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.stream.Collectors;

@DefaultBean
@ApplicationScoped
public class ConfigFileAgentProviderConfigSource implements AgentProviderConfigSource {

    private final OpenClawCasehubConfig config;

    @Inject
    public ConfigFileAgentProviderConfigSource(OpenClawCasehubConfig config) {
        this.config = config;
    }

    @Override
    public Map<String, AgentConfig> allAgents() {
        return config.agents().entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> new AgentConfig(
                                e.getValue().sessionKey(),
                                e.getValue().capabilities())));
    }
}
