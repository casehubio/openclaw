package io.casehub.openclaw.app.mcp;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.message.CommitmentState;
import io.casehub.qhorus.runtime.message.Commitment;
import io.casehub.qhorus.runtime.store.CommitmentStore;
import io.quarkiverse.mcp.server.ToolResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for casehub_status tool.
 *
 * <p>casehub_status resolves a commitment by correlationId and returns its current state.
 */
class QueryToolsTest {

    CommitmentStore commitmentStore;
    QueryTools tools;

    @BeforeEach
    void setUp() {
        commitmentStore = mock(CommitmentStore.class);
        tools = new QueryTools(commitmentStore);
    }

    @Test
    void status_byCorrelationId_returnsCurrentState() {
        UUID channelId = UUID.randomUUID();
        String correlationId = UUID.randomUUID().toString();
        Commitment c = commitment(correlationId, channelId, "finance-agent", CommitmentState.OPEN);
        when(commitmentStore.findByCorrelationId(correlationId)).thenReturn(Optional.of(c));

        ToolResponse response = tools.status("finance-agent", correlationId);

        assertThat(response.isError()).isFalse();
        String text = text(response);
        assertThat(text).contains("OPEN");
        assertThat(text).contains(correlationId);
    }

    @Test
    void status_unknownCorrelationId_returnsNotFoundError() {
        when(commitmentStore.findByCorrelationId("unknown")).thenReturn(Optional.empty());

        ToolResponse response = tools.status("agent", "unknown");

        assertThat(response.isError()).isTrue();
        assertThat(text(response)).contains("NOT_FOUND");
    }

    @Test
    void status_fulfilledCommitment_includesState() {
        String correlationId = UUID.randomUUID().toString();
        Commitment c = commitment(correlationId, UUID.randomUUID(), "home-agent",
                CommitmentState.FULFILLED);
        when(commitmentStore.findByCorrelationId(correlationId)).thenReturn(Optional.of(c));

        ToolResponse response = tools.status("home-agent", correlationId);

        assertThat(response.isError()).isFalse();
        assertThat(text(response)).contains("FULFILLED");
    }

    @Test
    void status_openCommitmentWithDeadline_includesDeadline() {
        String correlationId = UUID.randomUUID().toString();
        Commitment c = commitment(correlationId, UUID.randomUUID(), "home-agent", CommitmentState.OPEN);
        c.expiresAt = java.time.Instant.parse("2026-06-04T17:00:00Z");
        when(commitmentStore.findByCorrelationId(correlationId)).thenReturn(Optional.of(c));

        ToolResponse response = tools.status("home-agent", correlationId);

        assertThat(response.isError()).isFalse();
        assertThat(text(response)).contains("2026-06-04");
    }

    // ---- helpers ----

    private static Commitment commitment(String correlationId, UUID channelId,
                                          String obligor, CommitmentState state) {
        Commitment c = new Commitment();
        c.id = UUID.randomUUID();
        c.correlationId = correlationId;
        c.channelId = channelId;
        c.obligor = obligor;
        c.state = state;
        return c;
    }

    private static String text(ToolResponse response) {
        return response.content().stream()
                .filter(c -> c instanceof io.quarkiverse.mcp.server.TextContent)
                .map(c -> ((io.quarkiverse.mcp.server.TextContent) c).text())
                .findFirst()
                .orElse("");
    }
}
