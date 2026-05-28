package io.casehub.openclaw.app;

import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import io.casehub.openclaw.context.ChannelContextWindowService;
import io.casehub.openclaw.context.WindowContent;

/**
 * REST endpoint for ChannelContextWindow queries.
 *
 * <p>Always returns 200. An unknown agentId is not an error — it is the fail-open state
 * ({@link WindowContent#noAssociation()}). The Python SDK treats agentHasAssociation=false
 * as a silent skip. 404 would be semantically wrong.
 *
 * <p>{@code since} defaults to 0 when omitted — returns all buffered messages.
 */
@Path("/channel-context")
@Produces(MediaType.APPLICATION_JSON)
public class ChannelContextWindowResource {

    @Inject
    ChannelContextWindowService service;

    @GET
    @Path("/{agentId}")
    public WindowContent query(
            @PathParam("agentId") String agentId,
            @QueryParam("since") @DefaultValue("0") long since) {
        return service.query(agentId, since);
    }
}
