package io.casehub.openclaw.app;

import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.openclaw.context.ChannelContextWindowService;
import io.quarkus.test.junit.QuarkusTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that two tenants sharing the same agentId have independent
 * ChannelContextWindow entries — no cross-tenant leakage.
 *
 * <p>ChannelContextWindowService uses AgentKey(agentId, tenancyId) as the map key,
 * so two bindings with the same agentId but different tenancyId values are
 * completely independent entries. This test confirms that invariant.
 */
@QuarkusTest
class CrossTenantContextIsolationTest {

    @Inject
    ChannelContextWindowService contextWindowService;

    @Test
    void sameAgentId_differentTenants_independentContextWindows() {
        UUID caseA = UUID.randomUUID();
        UUID caseB = UUID.randomUUID();
        UUID channelA = UUID.randomUUID();

        // Bind agent "bot" for tenant-A
        contextWindowService.bindAgent("bot", "tenant-A", caseA);
        contextWindowService.bindChannel(caseA, channelA);

        // Bind agent "bot" for tenant-B — separate context window
        contextWindowService.bindAgent("bot", "tenant-B", caseB);

        // Query as tenant-A: association exists
        assertThat(contextWindowService.query("bot", "tenant-A", 0L).agentHasAssociation()).isTrue();

        // Query as tenant-B: associated but empty window (no channels bound)
        assertThat(contextWindowService.query("bot", "tenant-B", 0L).agentHasAssociation()).isTrue();

        // Unbind tenant-A: tenant-B unaffected
        contextWindowService.unbindAgent("bot", "tenant-A");
        assertThat(contextWindowService.query("bot", "tenant-A", 0L).agentHasAssociation()).isFalse();
        assertThat(contextWindowService.query("bot", "tenant-B", 0L).agentHasAssociation()).isTrue();
    }
}
