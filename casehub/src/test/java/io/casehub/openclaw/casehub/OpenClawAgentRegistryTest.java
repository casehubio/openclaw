package io.casehub.openclaw.casehub;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
}
