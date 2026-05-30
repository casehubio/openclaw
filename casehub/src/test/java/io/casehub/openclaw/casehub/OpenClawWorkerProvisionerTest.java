package io.casehub.openclaw.casehub;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.api.model.ProvisionContext;
import io.casehub.api.spi.ProvisionResult;
import io.casehub.api.spi.ProvisioningException;
import io.casehub.openclaw.context.ChannelContextWindowService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OpenClawWorkerProvisionerTest {

    ChannelContextWindowService mockService;
    OpenClawAgentRegistry registry;
    OpenClawWorkerProvisioner provisioner;
    OpenClawCasehubConfig config;

    @BeforeEach
    void setup() {
        mockService = mock(ChannelContextWindowService.class);
        registry = new OpenClawAgentRegistry();
        config = buildConfig(Map.of(
                "finance-agent", entry(List.of("finance", "banking"), "finance-agent"),
                "code-review-agent", entry(List.of("code-review"), "cr-agent-main")));
        provisioner = new OpenClawWorkerProvisioner(mockService, registry, config);
    }

    @Test
    void provision_singleCapabilityMatch_registersAgentInRegistry() {
        UUID caseId = UUID.randomUUID();
        provisioner.provision(Set.of("finance"), ctx(caseId));
        assertThat(registry.findAgentId(caseId)).contains("finance-agent");
    }

    @Test
    void provision_subsetMatch_selectsCorrectAgent() {
        UUID caseId = UUID.randomUUID();
        provisioner.provision(Set.of("finance", "banking"), ctx(caseId));
        assertThat(registry.findAgentId(caseId)).contains("finance-agent");
    }

    @Test
    void provision_callsBindAgent_onContextWindowService() {
        UUID caseId = UUID.randomUUID();
        provisioner.provision(Set.of("code-review"), ctx(caseId));
        verify(mockService).bindAgent("code-review-agent", caseId);
    }

    @Test
    void provision_registersSessionKeyInRegistry() {
        UUID caseId = UUID.randomUUID();
        provisioner.provision(Set.of("code-review"), ctx(caseId));
        assertThat(registry.findSessionKey("code-review-agent")).contains("cr-agent-main");
    }

    @Test
    void provision_unknownCapability_throwsProvisioningException() {
        assertThatThrownBy(() ->
                provisioner.provision(Set.of("unknown-cap"), ctx(UUID.randomUUID())))
                .isInstanceOf(ProvisioningException.class)
                .hasMessageContaining("unknown-cap");
    }

    @Test
    void provision_returnsEmptyProvisionResult() {
        ProvisionResult result = provisioner.provision(Set.of("finance"), ctx(UUID.randomUUID()));
        assertThat(result).isEqualTo(ProvisionResult.empty());
    }

    @Test
    void getCapabilities_returnsAllConfiguredCapabilities() {
        assertThat(provisioner.getCapabilities())
                .containsExactlyInAnyOrder("finance", "banking", "code-review");
    }

    @Test
    void terminate_deregistersFromRegistry() {
        UUID caseId = UUID.randomUUID();
        provisioner.provision(Set.of("finance"), ctx(caseId));
        provisioner.terminate("finance-agent");
        assertThat(registry.findAgentId(caseId)).isEmpty();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ProvisionContext ctx(UUID caseId) {
        return new ProvisionContext(caseId, "finance", null, null, null, null);
    }

    private OpenClawCasehubConfig buildConfig(Map<String, OpenClawCasehubConfig.AgentEntry> agents) {
        return new OpenClawCasehubConfig() {
            @Override public Map<String, AgentEntry> agents() { return agents; }
            @Override public Oversight oversight() { return () -> ""; }
        };
    }

    private OpenClawCasehubConfig.AgentEntry entry(List<String> caps, String sk) {
        return new OpenClawCasehubConfig.AgentEntry() {
            @Override public List<String> capabilities() { return caps; }
            @Override public String sessionKey() { return sk; }
        };
    }
}
