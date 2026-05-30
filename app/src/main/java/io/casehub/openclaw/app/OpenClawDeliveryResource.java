package io.casehub.openclaw.app;

import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.jboss.logging.Logger;

import io.casehub.openclaw.casehub.OversightGateService;
import io.casehub.qhorus.runtime.channel.ChannelService;

/**
 * Receives OpenClaw agent results delivered via deliver:webhook.
 *
 * <p>Stays thin: validates channelId, confirms channel exists, delegates to
 * {@link OversightGateService#evaluate(UUID, String, String)} which owns classification
 * and gate logic. Always returns 200 on processing failures — OpenClaw must not retry.
 *
 * <p>Phase 1 speech act classification: always STATUS (DONE requires inReplyTo which is
 * not available at delivery time — tracked in openclaw#16). See openclaw#10 for Phase 2/3.
 * No auth — follows gateway topology. See auth-retrofit-readiness.md protocol.
 */
@Path("/openclaw/delivery/channel")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class OpenClawDeliveryResource {

    private static final Logger log = Logger.getLogger(OpenClawDeliveryResource.class);

    @Inject
    ChannelService channelService;

    @Inject
    OversightGateService oversightGateService;

    @POST
    @Path("/{channelId}")
    public Response deliver(@PathParam("channelId") String channelIdStr,
                             OpenClawDeliveryPayload payload) {
        UUID channelId;
        try {
            channelId = UUID.fromString(channelIdStr);
        } catch (IllegalArgumentException e) {
            return Response.status(400).build();
        }

        if (channelService.findById(channelId).isEmpty()) {
            log.warnf("Delivery received for unknown channelId=%s", channelId);
            return Response.status(404).build();
        }

        String agentId = payload != null && payload.agentId() != null ? payload.agentId() : "openclaw-agent";
        String output = payload != null && payload.output() != null ? payload.output() : "";

        oversightGateService.evaluate(channelId, agentId, output);

        return Response.ok().build();
    }
}
