package io.casehub.openclaw.app.api;

import io.casehub.openclaw.app.mcp.WorkitemTools;
import io.casehub.platform.api.mcp.ApiResult;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PlatformMutation;
import io.casehub.platform.api.mcp.RestPath;
import io.quarkiverse.mcp.server.ToolResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

@McpDomain(value = "openclaw/workitems", app = "openclaw", basePath = "/api/openclaw/workitems", summary = "Contract-triggered work item lifecycle")
@ApplicationScoped
public class OpenClawWorkitemApi {

    @Inject WorkitemTools workitemTools;

    @PlatformMutation("Create a work item and dispatch it")
    @RestPath("/create")
    public ApiResult createWorkitem(String agentId, String description,
                                         String deadline, String assignee, String queueName) {
        ToolResponse r = workitemTools.createWorkitem(agentId, description, deadline, assignee, queueName);
        return toResult(r);
    }

    @PlatformMutation("Route a work item to a named queue")
    @RestPath("/queue")
    public ApiResult queue(String agentId, String description,
                                String queueName, Optional<String> priority) {
        ToolResponse r = workitemTools.queue(agentId, description, queueName, priority.orElse(null));
        return toResult(r);
    }

    private static ApiResult toResult(ToolResponse r) {
        String text = r.firstContent().asText().text();
        boolean ok = !r.isError() && !text.startsWith("ERROR");
        return new ApiResult(ok, null, text);
    }
}
