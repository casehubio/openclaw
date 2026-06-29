package io.casehub.openclaw.casehub;

import io.casehub.api.spi.ProvisionerConfigRegistry;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.*;

@ApplicationScoped
public class OpenClawAgentConfigResolver {

    private static final Logger LOG = Logger.getLogger(OpenClawAgentConfigResolver.class);
    private static final String PROVIDER = "openclaw";
    static final String KEY_SESSION_KEY = "sessionKey";
    static final String KEY_CAPABILITIES = "capabilities";

    private final ProvisionerConfigRegistry registry;
    private final OpenClawCasehubConfig localConfig;

    @Inject
    public OpenClawAgentConfigResolver(
            ProvisionerConfigRegistry registry,
            OpenClawCasehubConfig localConfig) {
        this.registry = registry;
        this.localConfig = localConfig;
    }

    public record AgentConfig(String sessionKey, List<String> capabilities) {}

    void onStartup(@Observes StartupEvent event) {
        Set<String> agentIds = registry.declaredAgentIds(PROVIDER);
        if (agentIds.isEmpty()) {
            return;
        }
        for (String agentId : agentIds) {
            fromRaw(agentId, registry.configFor(PROVIDER, agentId));
        }
        LOG.infof("Validated %d registry agent configs for provider '%s'",
                agentIds.size(), PROVIDER);
    }

    public Map<String, AgentConfig> allAgents() {
        var result = new LinkedHashMap<String, AgentConfig>();
        for (var entry : localConfig.agents().entrySet()) {
            result.put(entry.getKey(), fromLocalEntry(entry.getValue()));
        }
        for (String agentId : registry.declaredAgentIds(PROVIDER)) {
            result.put(agentId, fromRegistry(agentId));
        }
        return Map.copyOf(result);
    }

    public AgentConfig configFor(String agentId) {
        Map<String, Object> raw = registry.configFor(PROVIDER, agentId);
        if (!raw.isEmpty()) {
            return fromRaw(agentId, raw);
        }
        OpenClawCasehubConfig.AgentEntry entry = localConfig.agents().get(agentId);
        if (entry == null) {
            throw new IllegalArgumentException("No config for agent: " + agentId);
        }
        return fromLocalEntry(entry);
    }

    public Set<String> agentIds() {
        var all = new HashSet<>(registry.declaredAgentIds(PROVIDER));
        all.addAll(localConfig.agents().keySet());
        return Set.copyOf(all);
    }

    private AgentConfig fromRegistry(String agentId) {
        return fromRaw(agentId, registry.configFor(PROVIDER, agentId));
    }

    private AgentConfig fromRaw(String agentId, Map<String, Object> raw) {
        Object sessionKeyObj = raw.get(KEY_SESSION_KEY);
        if (sessionKeyObj == null) {
            throw new IllegalArgumentException(
                "Agent '" + agentId + "': registry config missing required key '"
                + KEY_SESSION_KEY + "'; present keys: " + raw.keySet());
        }
        String sessionKey = String.valueOf(sessionKeyObj);

        Object capsObj = raw.get(KEY_CAPABILITIES);
        List<String> capabilities;
        if (capsObj instanceof List<?> list) {
            capabilities = list.stream().map(String::valueOf).toList();
        } else if (capsObj instanceof String s) {
            capabilities = List.of(s);
        } else if (capsObj == null) {
            capabilities = List.of();
        } else {
            throw new IllegalArgumentException(
                "Agent '" + agentId + "': registry config '" + KEY_CAPABILITIES
                + "' must be a List or String, got: "
                + capsObj.getClass().getName());
        }

        return new AgentConfig(sessionKey, capabilities);
    }

    private static AgentConfig fromLocalEntry(OpenClawCasehubConfig.AgentEntry entry) {
        return new AgentConfig(entry.sessionKey(), entry.capabilities());
    }
}
