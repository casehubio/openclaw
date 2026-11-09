package io.casehub.openclaw.app.api;

import io.casehub.openclaw.app.api.dto.CommitmentStatus;
import io.casehub.platform.api.mcp.ApiResult;
import io.casehub.openclaw.app.mcp.CommitmentTools;
import io.casehub.openclaw.app.mcp.QueryTools;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PlatformMutation;
import io.casehub.platform.api.mcp.PlatformQuery;
import io.casehub.platform.api.mcp.RestPath;
import io.quarkiverse.mcp.server.ToolResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

@McpDomain(value = "openclaw/commitments", app = "openclaw", basePath = "/api/openclaw/commitments", summary = "Commitment lifecycle — create, fulfill, breach, compensate")
@ApplicationScoped
public class OpenClawCommitmentApi {

    @Inject CommitmentTools commitmentTools;
    @Inject QueryTools queryTools;

    @PlatformMutation("Register a commitment and arm the Watchdog")
    @RestPath("/commit")
    public ApiResult commit(String agentId, String task, Optional<String> deadline, Optional<String> channelId) {
        ToolResponse r = commitmentTools.commit(agentId, task, deadline.orElse(null), channelId.orElse(null));
        return toResult(r);
    }

    @PlatformMutation("Mark a commitment as done")
    @RestPath("/done")
    public ApiResult done(String agentId, String commitmentId, String outcome) {
        ToolResponse r = commitmentTools.done(agentId, commitmentId, outcome);
        return toResult(r);
    }

    @PlatformMutation("Reject a commitment")
    @RestPath("/reject")
    public ApiResult reject(String agentId, String commitmentId, String reason) {
        ToolResponse r = commitmentTools.reject(agentId, commitmentId, reason);
        return toResult(r);
    }

    @PlatformMutation("Report progress on a commitment")
    @RestPath("/checkpoint")
    public ApiResult checkpoint(String agentId, String commitmentId, String note) {
        ToolResponse r = commitmentTools.checkpoint(agentId, commitmentId, note);
        return toResult(r);
    }

    @PlatformMutation("Escalate a commitment to another agent or human")
    @RestPath("/escalate")
    public ApiResult escalate(String agentId, String commitmentId, String reason, Optional<String> toAgent) {
        ToolResponse r = commitmentTools.escalate(agentId, commitmentId, reason, toAgent.orElse(null));
        return toResult(r);
    }

    @PlatformMutation("Block a commitment with an extended deadline")
    @RestPath("/block")
    public ApiResult block(String agentId, String commitmentId, String reason, Optional<String> blockedUntil) {
        ToolResponse r = commitmentTools.block(agentId, commitmentId, reason, blockedUntil.orElse(null));
        return toResult(r);
    }

    @PlatformMutation("Delegate a commitment to a named agent")
    @RestPath("/delegate")
    public ApiResult delegate(String agentId, String commitmentId, String reason, Optional<String> toAgent) {
        ToolResponse r = commitmentTools.delegate(agentId, commitmentId, reason, toAgent.orElse(null));
        return toResult(r);
    }

    @PlatformQuery("Query commitment status")
    @RestPath("/status")
    public ApiResult status(String agentId, String commitmentId) {
        ToolResponse r = queryTools.status(agentId, commitmentId);
        return toResult(r);
    }

    private static ApiResult toResult(ToolResponse r) {
        String text = r.firstContent().asText().text();
        boolean ok = !r.isError() && !text.startsWith("ERROR");
        String id = null;
        if (text.contains("commitmentId=")) {
            int start = text.indexOf("commitmentId=") + 13;
            int end = text.indexOf(" ", start);
            id = end > start ? text.substring(start, end) : text.substring(start);
        }
        return new ApiResult(ok, id, text);
    }
}
