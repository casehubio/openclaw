package io.casehub.openclaw.app.mcp;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.casehub.qhorus.runtime.message.Commitment;
import io.casehub.qhorus.runtime.store.CommitmentStore;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolResponse;

/**
 * MCP tools for querying CaseHub state.
 *
 * <p>Stateless — read-only queries with no side effects.
 */
@ApplicationScoped
public class QueryTools {

    private final CommitmentStore commitmentStore;

    @Inject
    public QueryTools(CommitmentStore commitmentStore) {
        this.commitmentStore = commitmentStore;
    }

    @Tool(description = "Query the current status of a CaseHub commitment by commitmentId "
            + "(the correlationId returned by casehub_commit). Returns state, obligor, "
            + "and deadline.")
    public ToolResponse status(
            // agentId is reserved for future per-agent access scoping; currently unused
            @ToolArg(description = "Your OpenClaw agentId") String agentId,
            @ToolArg(description = "commitmentId returned by casehub_commit") String commitmentId) {

        Optional<Commitment> found = commitmentStore.findByCorrelationId(commitmentId);
        if (found.isEmpty()) {
            return ToolResponse.error("NOT_FOUND: no commitment with id '" + commitmentId + "'");
        }

        Commitment c = found.get();
        String deadline = c.expiresAt != null ? c.expiresAt.toString() : "none";
        String pendingActions = "OPEN".equals(c.state.name())
                ? "[\"await DONE or DECLINE\"]"
                : "[]";

        return ToolResponse.success("""
                {"id": "%s", "kind": "commitment", "state": "%s", "obligor": "%s", "deadline": "%s", "pendingActions": %s}
                """.formatted(commitmentId, c.state.name(),
                c.obligor != null ? c.obligor : "unassigned",
                deadline, pendingActions).strip());
    }
}
