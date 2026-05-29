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
        registry.register("agent-1", caseId, "session-key-1");
        assertThat(registry.findAgentId(caseId)).contains("agent-1");
    }

    @Test
    void register_findSessionKey_roundTrip() {
        UUID caseId = UUID.randomUUID();
        registry.register("agent-1", caseId, "session-key-1");
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
        registry.register("agent-1", caseId, "sk");
        registry.deregister("agent-1");
        assertThat(registry.findAgentId(caseId)).isEmpty();
        assertThat(registry.findSessionKey("agent-1")).isEmpty();
    }

    @Test
    void register_overwrite_updatesSessionKey() {
        UUID caseId = UUID.randomUUID();
        registry.register("agent-1", caseId, "sk-1");
        registry.register("agent-1", caseId, "sk-2");
        assertThat(registry.findSessionKey("agent-1")).contains("sk-2");
    }
}
