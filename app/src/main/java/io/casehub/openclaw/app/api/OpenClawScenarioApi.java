package io.casehub.openclaw.app.api;

import io.casehub.openclaw.app.scenario.ScenarioExecutionService;
import io.casehub.openclaw.casehub.OversightGateService;
import io.casehub.openclaw.casehub.scenario.CaseExecutionEvent;
import io.casehub.openclaw.casehub.scenario.ScenarioEventListener;
import io.casehub.openclaw.casehub.scenario.ScenarioStateSnapshot;
import io.casehub.openclaw.casehub.scenario.ScenarioStateStore;
import io.casehub.platform.api.mcp.ApiResult;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PathParam;
import io.casehub.platform.api.mcp.PlatformMutation;
import io.casehub.platform.api.mcp.PlatformQuery;
import io.casehub.platform.api.mcp.PlatformStream;
import io.casehub.platform.api.mcp.RestPath;
import io.smallrye.mutiny.Multi;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.UUID;

@McpDomain(value = "openclaw/scenarios", app = "openclaw", basePath = "/api/openclaw/scenarios", summary = "Contract scenario simulation and what-if analysis")
@ApplicationScoped
public class OpenClawScenarioApi {

    @Inject ScenarioStateStore stateStore;
    @Inject ScenarioExecutionService executionService;
    @Inject OversightGateService oversightGateService;

    @PlatformQuery("List all scenarios")
    @RestPath("/")
    @PermitAll
    public List<ScenarioStateSnapshot> listScenarios() {
        return stateStore.listScenarioSummaries();
    }

    @PlatformQuery("Get scenario state")
    @RestPath("/{id}/state")
    @PermitAll
    public ScenarioStateSnapshot getState(@PathParam String id) {
        return stateStore.currentState(id);
    }

    @PlatformMutation("Start a scenario")
    @RestPath("/{id}/start")
    @RolesAllowed("openclaw-admin")
    public ApiResult startScenario(@PathParam String id) {
        try {
            executionService.start(id);
            return new ApiResult(true, null, "Started");
        } catch (IllegalStateException e) {
            return new ApiResult(false, null, e.getMessage());
        } catch (IllegalArgumentException e) {
            return new ApiResult(false, null, e.getMessage());
        }
    }

    @PlatformMutation("Complete a work item gate in a scenario")
    @RestPath("/{id}/workitems/{gateId}/complete")
    @RolesAllowed("openclaw-admin")
    public ApiResult completeWorkitem(@PathParam String id, @PathParam String gateId,
                                           GateAction body) {
        String fulfillText = "approve".equalsIgnoreCase(body.action()) ? "Approved" : "Rejected";
        oversightGateService.fulfill(UUID.fromString(gateId), fulfillText);
        return new ApiResult(true, null, "Completed gate " + gateId);
    }

    @PlatformStream("Watch scenario execution events")
    @RestPath("/events")
    @PermitAll
    public Multi<CaseExecutionEvent> watchEvents() {
        return Multi.createFrom().<CaseExecutionEvent>emitter(emitter -> {
            ScenarioEventListener listener = emitter::emit;
            stateStore.addListener(listener);
            emitter.onTermination(() -> stateStore.removeListener(listener));
        });
    }

    public record GateAction(String action) {}
}
