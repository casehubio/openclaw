package io.casehub.openclaw.casehub;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OpenClawAgentRegistryTest {

    OpenClawAgentRegistry registry;

    @BeforeEach
    void setup() {
        registry = new OpenClawAgentRegistry();
    }

    @Test
    void register_findAgentId_roundTrip() {
        UUID caseId = UUID.randomUUID();
        registry.register("agent-1", "tenant-A", caseId, "session-key-1");
        assertThat(registry.findAgentId(caseId)).contains("agent-1");
    }

    @Test
    void register_findSessionKey_roundTrip() {
        UUID caseId = UUID.randomUUID();
        registry.register("agent-1", "tenant-A", caseId, "session-key-1");
        assertThat(registry.findSessionKey("agent-1")).contains("session-key-1");
    }

    @Test
    void findAgentId_unknownCase_returnsEmpty() {
        assertThat(registry.findAgentId(UUID.randomUUID())).isEmpty();
    }

    @Test
    void findSessionKey_unknownAgent_returnsEmpty() {
        assertThat(registry.findSessionKey("nobody")).isEmpty();
    }

    @Test
    void deregister_removesAllMappings() {
        UUID caseId = UUID.randomUUID();
        registry.register("agent-1", "tenant-A", caseId, "sk");
        registry.deregister("agent-1");
        assertThat(registry.findAgentId(caseId)).isEmpty();
        assertThat(registry.findSessionKey("agent-1")).isEmpty();
    }

    @Test
    void register_overwrite_updatesSessionKey() {
        UUID caseId = UUID.randomUUID();
        registry.register("agent-1", "tenant-A", caseId, "sk-1");
        registry.register("agent-1", "tenant-A", caseId, "sk-2");
        assertThat(registry.findSessionKey("agent-1")).contains("sk-2");
    }

    @Test
    void register_storesTenancyIdByCaseId() {
        UUID caseId = UUID.randomUUID();
        registry.register("agent-1", "tenant-A", caseId, "sk");
        assertThat(registry.findTenancyId(caseId)).contains("tenant-A");
    }

    @Test
    void deregister_removesTenancyEntry() {
        UUID caseId = UUID.randomUUID();
        registry.register("agent-1", "tenant-A", caseId, "sk");
        registry.deregister("agent-1");
        assertThat(registry.findTenancyId(caseId)).isEmpty();
    }

    @Test
    void findTenancyId_unknownCaseId_returnsEmpty() {
        assertThat(registry.findTenancyId(UUID.randomUUID())).isEmpty();
    }

    // --- 1:N tests ---

    @Test
    void register_multipleAgents_sameCaseId_allTracked() {
        UUID caseId = UUID.randomUUID();
        registry.register("agent-A", "tenant-A", caseId, "sk-A");
        registry.register("agent-B", "tenant-A", caseId, "sk-B");
        assertThat(registry.findAgentIds(caseId)).containsExactlyInAnyOrder("agent-A", "agent-B");
    }

    @Test
    void register_reRegisterDifferentCase_cleansOldCaseSet() {
        UUID caseX = UUID.randomUUID();
        UUID caseY = UUID.randomUUID();
        registry.register("agent-A", "tenant-A", caseX, "sk");
        registry.register("agent-A", "tenant-A", caseY, "sk");
        assertThat(registry.findAgentIds(caseX)).isEmpty();
        assertThat(registry.findTenancyId(caseX)).isEmpty();
        assertThat(registry.findAgentIds(caseY)).containsExactly("agent-A");
    }

    @Test
    void register_reRegisterDifferentCase_otherAgentSurvives() {
        UUID caseX = UUID.randomUUID();
        UUID caseY = UUID.randomUUID();
        registry.register("agent-A", "tenant-A", caseX, "sk-A");
        registry.register("agent-B", "tenant-A", caseX, "sk-B");
        registry.register("agent-A", "tenant-A", caseY, "sk-A");
        assertThat(registry.findAgentIds(caseX)).containsExactly("agent-B");
        assertThat(registry.findTenancyId(caseX)).contains("tenant-A");
        assertThat(registry.findAgentIds(caseY)).containsExactly("agent-A");
    }

    @Test
    void deregister_oneOfTwo_otherSurvives() {
        UUID caseId = UUID.randomUUID();
        registry.register("agent-A", "tenant-A", caseId, "sk-A");
        registry.register("agent-B", "tenant-A", caseId, "sk-B");
        var result = registry.deregister("agent-A");
        assertThat(result.caseId()).isEqualTo(caseId);
        assertThat(result.wasLastAgent()).isFalse();
        assertThat(registry.findAgentIds(caseId)).containsExactly("agent-B");
        assertThat(registry.findTenancyId(caseId)).contains("tenant-A");
    }

    @Test
    void deregister_lastAgent_cleansCaseMappings() {
        UUID caseId = UUID.randomUUID();
        registry.register("agent-A", "tenant-A", caseId, "sk");
        var result = registry.deregister("agent-A");
        assertThat(result.caseId()).isEqualTo(caseId);
        assertThat(result.wasLastAgent()).isTrue();
        assertThat(registry.findAgentIds(caseId)).isEmpty();
        assertThat(registry.findTenancyId(caseId)).isEmpty();
        assertThat(registry.hasAgentsForCase(caseId)).isFalse();
    }

    @Test
    void deregister_unknownAgent_returnsNullCaseId() {
        var result = registry.deregister("ghost");
        assertThat(result.caseId()).isNull();
        assertThat(result.wasLastAgent()).isFalse();
    }

    @Test
    void findAgentId_multipleAgents_returnsOne() {
        UUID caseId = UUID.randomUUID();
        registry.register("agent-A", "tenant-A", caseId, "sk-A");
        registry.register("agent-B", "tenant-A", caseId, "sk-B");
        assertThat(registry.findAgentId(caseId)).isPresent();
        assertThat(registry.findAgentId(caseId).get()).isIn("agent-A", "agent-B");
    }

    @Test
    void findAgentIds_unknownCase_returnsEmptySet() {
        assertThat(registry.findAgentIds(UUID.randomUUID())).isEmpty();
    }

    @Test
    void hasAgentsForCase_registered_returnsTrue() {
        UUID caseId = UUID.randomUUID();
        registry.register("agent-A", "tenant-A", caseId, "sk");
        assertThat(registry.hasAgentsForCase(caseId)).isTrue();
    }

    @Test
    void hasAgentsForCase_afterFullDeregister_returnsFalse() {
        UUID caseId = UUID.randomUUID();
        registry.register("agent-A", "tenant-A", caseId, "sk");
        registry.deregister("agent-A");
        assertThat(registry.hasAgentsForCase(caseId)).isFalse();
    }

    @Test
    void concurrent_registerAndDeregister_noOrphanedEntries() throws Exception {
        int  threads      = 8;
        int  opsPerThread = 100;
        UUID caseId       = UUID.randomUUID();
        var  latch        = new java.util.concurrent.CountDownLatch(threads);
        var  errors       = new java.util.concurrent.atomic.AtomicInteger(0);

        for (int t = 0; t < threads; t++) {
            final int threadIdx = t;
            Thread.startVirtualThread(() -> {
                try {
                    latch.countDown();
                    latch.await();
                    for (int i = 0; i < opsPerThread; i++) {
                        String agentId = "agent-" + threadIdx + "-" + i;
                        registry.register(agentId, "tenant", caseId, "sk");
                        registry.deregister(agentId);
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });
        }
        Thread.sleep(2000);
        assertThat(errors.get()).isZero();
        assertThat(registry.findAgentIds(caseId)).isEmpty();
        assertThat(registry.hasAgentsForCase(caseId)).isFalse();
    }
}
