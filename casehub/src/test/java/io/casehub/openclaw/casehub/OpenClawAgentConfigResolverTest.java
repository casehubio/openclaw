package io.casehub.openclaw.casehub;

import io.casehub.api.spi.ProvisionerConfigRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class OpenClawAgentConfigResolverTest {

    @Test
    void registryPopulated_allAgents_returnsTypedConfigs() {
        var registry = mockRegistry(Map.of(
            "agent-1", Map.of("sessionKey", "sk1", "capabilities", List.of("finance"))
        ));
        var localConfig = mockConfig(Map.of());
        var resolver = new OpenClawAgentConfigResolver(registry, localConfig);

        var result = resolver.allAgents();

        assertEquals(1, result.size());
        var config = result.get("agent-1");
        assertNotNull(config);
        assertEquals("sk1", config.sessionKey());
        assertEquals(List.of("finance"), config.capabilities());
    }

    @Test
    void registryEmpty_allAgents_fallsBackToLocalConfig() {
        var registry = mockRegistry(Map.of());
        var localConfig = mockConfig(Map.of(
            "local-agent", mockAgentEntry("local-sk", List.of("local-cap"))
        ));
        var resolver = new OpenClawAgentConfigResolver(registry, localConfig);

        var result = resolver.allAgents();

        assertEquals(1, result.size());
        var config = result.get("local-agent");
        assertNotNull(config);
        assertEquals("local-sk", config.sessionKey());
        assertEquals(List.of("local-cap"), config.capabilities());
    }

    @Test
    void unionSemantics_agentIds_returnsBothSources() {
        var registry = mockRegistry(Map.of(
            "agent-a", Map.of("sessionKey", "sk-a", "capabilities", List.of())
        ));
        var localConfig = mockConfig(Map.of(
            "agent-b", mockAgentEntry("sk-b", List.of())
        ));
        var resolver = new OpenClawAgentConfigResolver(registry, localConfig);

        var result = resolver.agentIds();

        assertEquals(Set.of("agent-a", "agent-b"), result);
    }

    @Test
    void unionSemantics_allAgents_registryOverridesLocal() {
        var registry = mockRegistry(Map.of(
            "agent-a", Map.of("sessionKey", "registry-sk", "capabilities", List.of())
        ));
        var localConfig = mockConfig(Map.of(
            "agent-a", mockAgentEntry("local-sk", List.of())
        ));
        var resolver = new OpenClawAgentConfigResolver(registry, localConfig);

        var result = resolver.allAgents();

        assertEquals(1, result.size());
        assertEquals("registry-sk", result.get("agent-a").sessionKey());
    }

    @Test
    void configFor_registryFirst_thenLocalFallback() {
        var registry = mockRegistry(Map.of(
            "agent-a", Map.of("sessionKey", "sk-a", "capabilities", List.of())
        ));
        var localConfig = mockConfig(Map.of(
            "agent-b", mockAgentEntry("sk-b", List.of())
        ));
        var resolver = new OpenClawAgentConfigResolver(registry, localConfig);

        var resultA = resolver.configFor("agent-a");
        var resultB = resolver.configFor("agent-b");

        assertEquals("sk-a", resultA.sessionKey());
        assertEquals("sk-b", resultB.sessionKey());
    }

    @Test
    void configFor_unknownAgent_throwsIllegalArgument() {
        var registry = mockRegistry(Map.of());
        var localConfig = mockConfig(Map.of());
        var resolver = new OpenClawAgentConfigResolver(registry, localConfig);

        var ex = assertThrows(IllegalArgumentException.class,
            () -> resolver.configFor("ghost"));

        assertTrue(ex.getMessage().contains("ghost"));
    }

    @Test
    void fromRaw_missingSessionKey_throwsWithDescriptiveMessage() {
        var registry = mockRegistry(Map.of(
            "bad-agent", Map.of("capabilities", List.of("x"))
        ));
        var localConfig = mockConfig(Map.of());
        var resolver = new OpenClawAgentConfigResolver(registry, localConfig);

        var ex = assertThrows(IllegalArgumentException.class,
            () -> resolver.configFor("bad-agent"));

        assertTrue(ex.getMessage().contains("bad-agent"));
        assertTrue(ex.getMessage().contains("sessionKey"));
    }

    @Test
    void fromRaw_capabilitiesSingleString_coercedToList() {
        var registry = mockRegistry(Map.of(
            "agent", Map.of("sessionKey", "sk", "capabilities", "single-cap")
        ));
        var localConfig = mockConfig(Map.of());
        var resolver = new OpenClawAgentConfigResolver(registry, localConfig);

        var result = resolver.configFor("agent");

        assertEquals(List.of("single-cap"), result.capabilities());
    }

    @Test
    void fromRaw_capabilitiesAbsent_defaultsToEmptyList() {
        var registry = mockRegistry(Map.of(
            "agent", Map.of("sessionKey", "sk")
        ));
        var localConfig = mockConfig(Map.of());
        var resolver = new OpenClawAgentConfigResolver(registry, localConfig);

        var result = resolver.configFor("agent");

        assertEquals(List.of(), result.capabilities());
    }

    @Test
    void fromRaw_capabilitiesWrongType_throwsWithTypeName() {
        var registry = mockRegistry(Map.of(
            "agent", Map.of("sessionKey", "sk", "capabilities", 42)
        ));
        var localConfig = mockConfig(Map.of());
        var resolver = new OpenClawAgentConfigResolver(registry, localConfig);

        var ex = assertThrows(IllegalArgumentException.class,
            () -> resolver.configFor("agent"));

        assertTrue(ex.getMessage().contains("Integer"));
    }

    @Test
    void fromRaw_sessionKeyNonString_coercedViaValueOf() {
        var registry = mockRegistry(Map.of(
            "agent", Map.of("sessionKey", 123, "capabilities", List.of())
        ));
        var localConfig = mockConfig(Map.of());
        var resolver = new OpenClawAgentConfigResolver(registry, localConfig);

        var result = resolver.configFor("agent");

        assertEquals("123", result.sessionKey());
    }

    @Test
    void errorMessages_includeAgentId() {
        var registry = mockRegistry(Map.of(
            "specific-agent", Map.of("capabilities", List.of())
        ));
        var localConfig = mockConfig(Map.of());
        var resolver = new OpenClawAgentConfigResolver(registry, localConfig);

        var ex = assertThrows(IllegalArgumentException.class,
            () -> resolver.configFor("specific-agent"));

        assertTrue(ex.getMessage().contains("specific-agent"));
    }

    @Test
    void startupValidation_malformedAgent_throwsOnStartup() {
        var registry = mockRegistry(Map.of(
            "bad", Map.of("capabilities", List.of())
        ));
        var localConfig = mockConfig(Map.of());
        var resolver = new OpenClawAgentConfigResolver(registry, localConfig);

        assertThrows(IllegalArgumentException.class,
            () -> resolver.onStartup(null));
    }

    @Test
    void startupValidation_noOpRegistry_noError() {
        var registry = mockRegistry(Map.of());
        var localConfig = mockConfig(Map.of());
        var resolver = new OpenClawAgentConfigResolver(registry, localConfig);

        assertDoesNotThrow(() -> resolver.onStartup(null));
    }

    @Test
    void startupValidation_allValid_logsSuccessMessage() {
        var registry = mockRegistry(Map.of(
            "agent-1", Map.of("sessionKey", "sk1", "capabilities", List.of()),
            "agent-2", Map.of("sessionKey", "sk2", "capabilities", List.of())
        ));
        var localConfig = mockConfig(Map.of());
        var resolver = new OpenClawAgentConfigResolver(registry, localConfig);

        assertDoesNotThrow(() -> resolver.onStartup(null));
    }

    // Mock helpers

    private static ProvisionerConfigRegistry mockRegistry(Map<String, Map<String, Object>> agents) {
        return new ProvisionerConfigRegistry() {
            @Override
            public Map<String, Object> configFor(String prov, String id) {
                return agents.getOrDefault(id, Map.of());
            }

            @Override
            public Set<String> declaredAgentIds(String prov) {
                return agents.keySet();
            }
        };
    }

    private static OpenClawCasehubConfig mockConfig(Map<String, OpenClawCasehubConfig.AgentEntry> agents) {
        return new OpenClawCasehubConfig() {
            @Override
            public Map<String, AgentEntry> agents() {
                return agents;
            }

            @Override
            public Oversight oversight() {
                return new Oversight() {
                    @Override
                    public Optional<String> agentId() {
                        return Optional.empty();
                    }
                };
            }
        };
    }

    private static OpenClawCasehubConfig.AgentEntry mockAgentEntry(String sessionKey, List<String> capabilities) {
        return new OpenClawCasehubConfig.AgentEntry() {
            @Override
            public List<String> capabilities() {
                return capabilities;
            }

            @Override
            public String sessionKey() {
                return sessionKey;
            }
        };
    }
}
