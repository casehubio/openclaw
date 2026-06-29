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
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReactiveOpenClawWorkerProvisionerTest {

    ChannelContextWindowService mockService;
    OpenClawAgentRegistry registry;
    ReactiveOpenClawWorkerProvisioner provisioner;
    OpenClawAgentConfigResolver resolver;
    CurrentPrincipal mockPrincipal;

    @BeforeEach
    void setup() {
        mockService = mock(ChannelContextWindowService.class);
        registry = new OpenClawAgentRegistry();
        resolver = buildResolver(Map.of(
                "finance-agent",     new OpenClawAgentConfigResolver.AgentConfig("finance-agent", List.of("finance", "banking")),
                "code-review-agent", new OpenClawAgentConfigResolver.AgentConfig("cr-agent-main", List.of("code-review"))));
        mockPrincipal = mock(CurrentPrincipal.class);
        when(mockPrincipal.tenancyId()).thenReturn("test-tenant");
        provisioner = new ReactiveOpenClawWorkerProvisioner(mockService, registry, resolver, mockPrincipal);
    }

    // ── provision ────────────────────────────────────────────────────────────

    @Test
    void provision_singleCapabilityMatch_registersAgentInRegistry() {
        UUID caseId = UUID.randomUUID();

        provisioner.provision(Set.of("finance"), ctx(caseId))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem();

        assertThat(registry.findAgentId(caseId)).contains("finance-agent");
    }

    @Test
    void provision_subsetMatch_selectsCorrectAgent() {
        UUID caseId = UUID.randomUUID();

        provisioner.provision(Set.of("finance", "banking"), ctx(caseId))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem();

        assertThat(registry.findAgentId(caseId)).contains("finance-agent");
    }

    @Test
    void provision_callsBindAgent_onContextWindowService() {
        UUID caseId = UUID.randomUUID();

        provisioner.provision(Set.of("code-review"), ctx(caseId))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem();

        verify(mockService).bindAgent("code-review-agent", caseId);
    }

    @Test
    void provision_registersSessionKey() {
        UUID caseId = UUID.randomUUID();

        provisioner.provision(Set.of("code-review"), ctx(caseId))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem();

        assertThat(registry.findSessionKey("code-review-agent")).contains("cr-agent-main");
    }

    @Test
    void provision_returnsEmptyProvisionResult() {
        provisioner.provision(Set.of("finance"), ctx(UUID.randomUUID()))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertItem(ProvisionResult.empty());
    }

    @Test
    void provision_unknownCapability_emitsProvisioningExceptionFailure() {
        provisioner.provision(Set.of("unknown-cap"), ctx(UUID.randomUUID()))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .assertFailedWith(ProvisioningException.class, "unknown-cap");
    }

    // ── getCapabilities ───────────────────────────────────────────────────────

    @Test
    void getCapabilities_returnsAllConfiguredCapabilities() {
        Set<String> caps = provisioner.getCapabilities()
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertThat(caps).containsExactlyInAnyOrder("finance", "banking", "code-review");
    }

    // ── terminate ─────────────────────────────────────────────────────────────

    @Test
    void terminate_deregistersFromRegistry() {
        UUID caseId = UUID.randomUUID();
        provisioner.provision(Set.of("finance"), ctx(caseId))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem();

        provisioner.terminate("finance-agent", "test-tenant")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem();

        assertThat(registry.findAgentId(caseId)).isEmpty();
    }

    @Test
    void provision_storesTenancyIdInRegistry() {
        UUID caseId = UUID.randomUUID();
        provisioner.provision(Set.of("finance"), ctx(caseId))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem();
        assertThat(registry.findTenancyId(caseId)).contains("test-tenant");
    }

    @Test
    void terminate_unbindsAgentWithTenancyId() {
        UUID caseId = UUID.randomUUID();
        provisioner.provision(Set.of("finance"), ctx(caseId))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem();
        provisioner.terminate("finance-agent", "test-tenant")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem();
        verify(mockService).unbindAgent("finance-agent");
    }

    @Test
    void terminate_unknownAgent_stillCallsUnbindAgent() {
        // never provisioned — service must still be called
        provisioner.terminate("not-registered", null)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem();
        verify(mockService).unbindAgent("not-registered");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ProvisionContext ctx(UUID caseId) {
        return new ProvisionContext(caseId, "finance", null, null, null, null, null);
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
