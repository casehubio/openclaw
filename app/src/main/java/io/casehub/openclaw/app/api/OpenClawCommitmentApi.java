package io.casehub.openclaw.app.api;

import io.casehub.openclaw.app.api.dto.CommitmentResult;
import io.casehub.openclaw.app.api.dto.CommitmentStatus;
import io.casehub.openclaw.app.mcp.CommitmentTools;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PlatformMutation;
import io.casehub.platform.api.mcp.PlatformQuery;
import io.casehub.platform.api.mcp.RestPath;
import io.casehub.qhorus.api.message.Commitment;
import io.casehub.qhorus.api.store.CommitmentStore;
import io.quarkiverse.mcp.server.ToolResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

@McpDomain(value = "openclaw/commitments", app = "openclaw", basePath = "/api/openclaw/commitments", summary = "Commitment lifecycle — create, fulfill, breach, compensate")
@ApplicationScoped
public class OpenClawCommitmentApi {

    @Inject CommitmentTools commitmentTools;
    @Inject CommitmentStore commitmentStore;

    @PlatformMutation("Register a commitment and arm the Watchdog")
    @RestPath("/commit")
    public CommitmentResult commit(String agentId, String task, Optional<String> deadline, Optional<String> channelId) {
        ToolResponse r = commitmentTools.commit(agentId, task, deadline.orElse(null), channelId.orElse(null));
        return toCommitmentResult(r);
    }

    @PlatformMutation("Mark a commitment as done")
    @RestPath("/done")
    public CommitmentResult done(String agentId, String commitmentId, String outcome) {
        ToolResponse r = commitmentTools.done(agentId, commitmentId, outcome);
        return toCommitmentResult(commitmentId, r);
    }

    @PlatformMutation("Reject a commitment")
    @RestPath("/reject")
    public CommitmentResult reject(String agentId, String commitmentId, String reason) {
        ToolResponse r = commitmentTools.reject(agentId, commitmentId, reason);
        return toCommitmentResult(commitmentId, r);
    }

    @PlatformMutation("Report progress on a commitment")
    @RestPath("/checkpoint")
    public CommitmentResult checkpoint(String agentId, String commitmentId, String note) {
        ToolResponse r = commitmentTools.checkpoint(agentId, commitmentId, note);
        return toCommitmentResult(commitmentId, r);
    }

    @PlatformMutation("Escalate a commitment to another agent or human")
    @RestPath("/escalate")
    public CommitmentResult escalate(String agentId, String commitmentId, String reason, Optional<String> toAgent) {
        ToolResponse r = commitmentTools.escalate(agentId, commitmentId, reason, toAgent.orElse(null));
        return toCommitmentResult(commitmentId, r);
    }

    @PlatformMutation("Block a commitment with an extended deadline")
    @RestPath("/block")
    public CommitmentResult block(String agentId, String commitmentId, String reason, Optional<String> blockedUntil) {
        ToolResponse r = commitmentTools.block(agentId, commitmentId, reason, blockedUntil.orElse(null));
        return toCommitmentResult(commitmentId, r);
    }

    @PlatformMutation("Delegate a commitment to a named agent")
    @RestPath("/delegate")
    public CommitmentResult delegate(String agentId, String commitmentId, String reason, Optional<String> toAgent) {
        ToolResponse r = commitmentTools.delegate(agentId, commitmentId, reason, toAgent.orElse(null));
        return toCommitmentResult(commitmentId, r);
    }

    @PlatformQuery("Query commitment status — returns state, deadline, obligor")
    @RestPath("/status")
    public CommitmentStatus status(String agentId, String commitmentId) {
        Commitment c = commitmentStore.findByCorrelationId(commitmentId)
                .orElseThrow(() -> new IllegalArgumentException("Commitment not found: " + commitmentId));
        return new CommitmentStatus(commitmentId, c.obligor(), null,
                c.state().name(), c.expiresAt(), null);
    }

    private static CommitmentResult toCommitmentResult(ToolResponse r) {
        String text = r.firstContent().asText().text();
        boolean ok = !r.isError() && !text.startsWith("ERROR");
        String id = extractJsonField(text, "commitmentId");
        return new CommitmentResult(ok, id, text);
    }

    private static CommitmentResult toCommitmentResult(String commitmentId, ToolResponse r) {
        String text = r.firstContent().asText().text();
        boolean ok = !r.isError() && !text.startsWith("ERROR");
        return new CommitmentResult(ok, commitmentId, text);
    }

    private static String extractJsonField(String json, String field) {
        String key = "\"" + field + "\": \"";
        int start = json.indexOf(key);
        if (start < 0) return null;
        start += key.length();
        int end = json.indexOf("\"", start);
        return end > start ? json.substring(start, end) : null;
    }
}
