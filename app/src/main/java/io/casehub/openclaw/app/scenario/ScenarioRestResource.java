package io.casehub.openclaw.app.scenario;

import java.util.List;

import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import io.casehub.openclaw.app.OpenClawGroups;
import io.casehub.openclaw.casehub.OversightGateService;
import io.casehub.openclaw.casehub.scenario.ScenarioStateSnapshot;
import io.casehub.openclaw.casehub.scenario.ScenarioStateStore;

import java.util.UUID;

@ApplicationScoped
@Path("/api/scenarios")
@Produces(MediaType.APPLICATION_JSON)
public class ScenarioRestResource {

    private final ScenarioStateStore stateStore;
    private final ScenarioExecutionService executionService;
    private final OversightGateService oversightGateService;

    @Inject
    public ScenarioRestResource(ScenarioStateStore stateStore,
                                 ScenarioExecutionService executionService,
                                 OversightGateService oversightGateService) {
        this.stateStore = stateStore;
        this.executionService = executionService;
        this.oversightGateService = oversightGateService;
    }

    @GET
    @PermitAll
    public List<ScenarioStateSnapshot> list() {
        return stateStore.listScenarioSummaries();
    }

    @GET
    @Path("/{id}/state")
    @PermitAll
    public ScenarioStateSnapshot state(@PathParam("id") String id) {
        return stateStore.currentState(id);
    }

    @POST
    @Path("/{id}/start")
    @RolesAllowed(OpenClawGroups.ADMIN)
    public Response start(@PathParam("id") String id) {
        try {
            executionService.start(id);
            return Response.accepted().build();
        } catch (IllegalStateException e) {
            return Response.status(409).entity("{\"error\":\"" + e.getMessage() + "\"}").build();
        } catch (IllegalArgumentException e) {
            return Response.status(404).entity("{\"error\":\"" + e.getMessage() + "\"}").build();
        }
    }

    @POST
    @Path("/{id}/gate/{gateId}/approve")
    @RolesAllowed(OpenClawGroups.ADMIN)
    public Response approveGate(@PathParam("id") String scenarioId, @PathParam("gateId") String gateId) {
        oversightGateService.fulfill(UUID.fromString(gateId), "Approved");
        return Response.ok().build();
    }

    @POST
    @Path("/{id}/gate/{gateId}/reject")
    @RolesAllowed(OpenClawGroups.ADMIN)
    public Response rejectGate(@PathParam("id") String scenarioId, @PathParam("gateId") String gateId) {
        oversightGateService.fulfill(UUID.fromString(gateId), "Rejected");
        return Response.ok().build();
    }
}
