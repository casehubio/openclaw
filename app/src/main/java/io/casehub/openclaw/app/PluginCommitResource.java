package io.casehub.openclaw.app;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.jboss.logging.Logger;

import io.casehub.qhorus.api.message.CommitmentState;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.message.Commitment;
import io.casehub.qhorus.runtime.message.CommitmentService;
import io.casehub.qhorus.runtime.store.CommitmentStore;

/**
 * REST endpoints consumed by the TypeScript plugin for auto-commit lifecycle management.
 *
 * <p>These are NOT MCP tools and NOT for LLM use. They are called by the plugin's
 * {@code before_tool_call} and {@code agent_end} hooks to open and close commitments
 * automatically, without LLM involvement in state management.
 *
 * <ul>
 *   <li>{@code POST /openclaw/plugin/commit} — open a self-commit for an agent turn
 *   <li>{@code POST /openclaw/plugin/done} — close an auto-committed commitment
 *   <li>{@code GET /openclaw/plugin/commitments/{agentId}} — list open commitments for session_start injection
 * </ul>
 *
 * <p>@RolesAllowed(OpenClawGroups.PLUGIN): authenticated by PluginTokenBridgeMechanism
 * via pre-shared bearer token. Migrate to OIDC client-credentials when available (openclaw#52).
 */
@RolesAllowed(OpenClawGroups.PLUGIN)
@ApplicationScoped
@Path("/openclaw/plugin")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class PluginCommitResource {

    private static final Logger log = Logger.getLogger(PluginCommitResource.class);

    @Inject
    CommitmentService commitmentService;

    @Inject
    CommitmentStore commitmentStore;

    public record CommitRequest(String agentId, String task, String deadline) {}

    public record CommitResponse(String commitmentId, String watchdogDeadline) {}

    public record DoneRequest(String agentId, String commitmentId) {}

    public record DoneResponse(boolean closed) {}

    public record CommitmentEntry(String commitmentId, String state, String deadline, boolean watchdogArmed) {}

    public record CommitmentsResponse(List<CommitmentEntry> open, int count) {}

    @POST
    @Path("/commit")
    public Response commit(CommitRequest req) {
        if (req == null || req.agentId() == null || req.agentId().isBlank()) {
            return Response.status(400).entity("{\"error\": \"agentId is required\"}").build();
        }

        String correlationId = UUID.randomUUID().toString();
        Instant deadline = null;
        if (req.deadline() != null) {
            try {
                deadline = Instant.parse(req.deadline());
            } catch (DateTimeParseException e) {
                return Response.status(400)
                        .entity("{\"error\": \"INVALID_DEADLINE: '" + req.deadline()
                                + "' — use ISO-8601 format e.g. 2026-06-04T17:00:00Z\"}")
                        .build();
            }
        }

        Commitment c = commitmentService.open(
                UUID.randomUUID(),
                correlationId,
                null,
                MessageType.COMMAND,
                req.agentId(),
                req.agentId(),
                deadline);

        log.debugf("Plugin auto-commit: agentId=%s correlationId=%s", req.agentId(), correlationId);

        return Response.ok(new CommitResponse(
                c.correlationId,
                c.expiresAt != null ? c.expiresAt.toString() : "none")).build();
    }

    @POST
    @Path("/done")
    public Response done(DoneRequest req) {
        if (req == null || req.commitmentId() == null || req.commitmentId().isBlank()) {
            return Response.status(400).entity("{\"error\": \"commitmentId is required\"}").build();
        }

        commitmentService.fulfill(req.commitmentId());

        log.debugf("Plugin auto-done: agentId=%s commitmentId=%s", req.agentId(), req.commitmentId());

        return Response.ok(new DoneResponse(true)).build();
    }

    @GET
    @Path("/commitments/{agentId}")
    public CommitmentsResponse commitments(@PathParam("agentId") String agentId) {
        List<CommitmentEntry> open = commitmentStore.findAllOpen().stream()
                .filter(c -> agentId.equals(c.obligor))
                .filter(c -> c.state == CommitmentState.OPEN
                        || c.state == CommitmentState.ACKNOWLEDGED)
                .map(c -> new CommitmentEntry(
                        c.correlationId,
                        c.state.name(),
                        c.expiresAt != null ? c.expiresAt.toString() : "none",
                        true))
                .collect(Collectors.toList());

        return new CommitmentsResponse(open, open.size());
    }
}
