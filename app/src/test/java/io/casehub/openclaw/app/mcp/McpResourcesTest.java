package io.casehub.openclaw.app.mcp;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.openclaw.context.ChannelContextWindowService;
import io.casehub.openclaw.context.WindowContent;
import io.casehub.qhorus.api.message.CommitmentState;
import io.casehub.qhorus.runtime.message.Commitment;
import io.casehub.qhorus.runtime.store.CommitmentStore;
import io.quarkiverse.mcp.server.ResourceResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for MCP resources:
 * - casehub://agent/{agentId}/commitments — open commitment list
 * - casehub://channel/{channelId}/recent — channel context window
 */
class McpResourcesTest {

    CommitmentStore commitmentStore;
    ChannelContextWindowService contextWindowService;
    CasehubMcpResources resources;

    @BeforeEach
    void setUp() {
        commitmentStore = mock(CommitmentStore.class);
        contextWindowService = mock(ChannelContextWindowService.class);
        resources = new CasehubMcpResources(commitmentStore, contextWindowService);
    }

    // ---- casehub://agent/{agentId}/commitments ----

    @Test
    void agentCommitments_returnOnlyOpenAndEscalatedForObligor() {
        String agentId = "finance-agent";
        UUID channelId = UUID.randomUUID();

        Commitment open = commitment(UUID.randomUUID().toString(), channelId, agentId,
                CommitmentState.OPEN, Instant.parse("2026-06-04T17:00:00Z"));
        // findAllOpen() returns all non-terminal by obligor across channels
        when(commitmentStore.findAllOpen()).thenReturn(List.of(open));

        ResourceResponse response = resources.agentCommitments(agentId);

        String text = text(response);
        assertThat(text).contains("OPEN");
        assertThat(text).contains(open.correlationId);
        assertThat(text).contains("2026-06-04");
        assertThat(text).contains("\"count\": 1");
    }

    @Test
    void agentCommitments_noOpenCommitments_returnsEmptyList() {
        when(commitmentStore.findAllOpen()).thenReturn(List.of());

        ResourceResponse response = resources.agentCommitments("home-agent");

        assertThat(text(response)).contains("\"count\": 0");
        assertThat(text(response)).contains("\"open\": []");
    }

    @Test
    void agentCommitments_filtersOutCommitmentsForOtherAgents() {
        String agentId = "finance-agent";
        UUID channelId = UUID.randomUUID();

        // Two commitments: one for finance-agent, one for home-agent
        Commitment mine = commitment("corr-mine", channelId, agentId,
                CommitmentState.OPEN, Instant.now().plusSeconds(3600));
        Commitment other = commitment("corr-other", channelId, "home-agent",
                CommitmentState.OPEN, Instant.now().plusSeconds(3600));
        when(commitmentStore.findAllOpen()).thenReturn(List.of(mine, other));

        ResourceResponse response = resources.agentCommitments(agentId);

        String text = text(response);
        assertThat(text).contains("corr-mine");
        assertThat(text).doesNotContain("corr-other");
        assertThat(text).contains("\"count\": 1");
    }

    // ---- casehub://channel/{channelId}/recent ----

    @Test
    void channelRecent_returnsWindowContent() {
        String agentId = "finance-agent";
        WindowContent window = new WindowContent(
                List.of(), 0L, 0L, 0L, true, Instant.now());
        when(contextWindowService.query(eq(agentId), eq(0L))).thenReturn(window);

        ResourceResponse response = resources.channelRecent(agentId);

        assertThat(response).isNotNull();
        assertThat(text(response)).isNotNull();
    }

    // ---- helpers ----

    private static Commitment commitment(String correlationId, UUID channelId,
                                          String obligor, CommitmentState state, Instant expiresAt) {
        Commitment c = new Commitment();
        c.id = UUID.randomUUID();
        c.correlationId = correlationId;
        c.channelId = channelId;
        c.obligor = obligor;
        c.state = state;
        c.expiresAt = expiresAt;
        return c;
    }

    private static String text(ResourceResponse response) {
        if (response == null || response.contents() == null || response.contents().isEmpty()) {
            return "";
        }
        var first = response.contents().get(0);
        if (first instanceof io.quarkiverse.mcp.server.TextResourceContents t) {
            return t.text();
        }
        return "";
    }
}
