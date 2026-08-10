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
import io.casehub.platform.api.identity.CurrentPrincipal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenClawWorkerProvisionerTest {

    ChannelContextWindowService mockService;
    OpenClawAgentRegistry registry;
    OpenClawWorkerProvisioner provisioner;
    OpenClawAgentConfigResolver resolver;
    CurrentPrincipal mockPrincipal;

    @BeforeEach
    void setup() {
        mockService = mock(ChannelContextWindowService.class);
        registry = new OpenClawAgentRegistry();
        resolver = buildResolver(Map.of(
                "finance-agent", new OpenClawAgentConfigResolver.AgentConfig("finance-agent", List.of("finance", "banking")),
                "code-review-agent", new OpenClawAgentConfigResolver.AgentConfig("cr-agent-main", List.of("code-review"))));
        mockPrincipal = mock(CurrentPrincipal.class);
        when(mockPrincipal.tenancyId()).thenReturn("test-tenant");
        provisioner = new OpenClawWorkerProvisioner(mockService, registry, resolver, mockPrincipal);
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
        provisioner.terminate("finance-agent", "test-tenant");
        assertThat(registry.findAgentId(caseId)).isEmpty();
    }

    @Test
    void provision_storesTenancyIdInRegistry() {
        UUID caseId = UUID.randomUUID();
        provisioner.provision(Set.of("finance"), ctx(caseId));
        assertThat(registry.findTenancyId(caseId)).contains("test-tenant");
    }

    @Test
    void terminate_unbindsAgentWithTenancyId() {
        UUID caseId = UUID.randomUUID();
        provisioner.provision(Set.of("finance"), ctx(caseId));
        provisioner.terminate("finance-agent", "test-tenant");
        verify(mockService).unbindAgent("finance-agent");
    }

    @Test
    void terminate_unknownAgent_stillCallsUnbindAgent() {
        provisioner.terminate("not-registered", null);
        verify(mockService).unbindAgent("not-registered");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ProvisionContext ctx(UUID caseId) {
        return new ProvisionContext(caseId, "finance", null, null, null, null, null, null);
    }

    private OpenClawAgentConfigResolver buildResolver(Map<String, OpenClawAgentConfigResolver.AgentConfig> agents) {
        OpenClawAgentConfigResolver mock = mock(OpenClawAgentConfigResolver.class);
        when(mock.allAgents()).thenReturn(agents);
        when(mock.configFor(anyString())).thenAnswer(inv -> {
            var cfg = agents.get(inv.getArgument(0));
            if (cfg == null) throw new IllegalArgumentException("No config for agent: " + inv.getArgument(0));
            return cfg;
        });
        return mock;
    }
}
