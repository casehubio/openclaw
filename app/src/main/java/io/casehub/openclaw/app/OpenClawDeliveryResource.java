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

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.message.MessageService;

/**
 * Receives OpenClaw agent results delivered via deliver:webhook and posts them as DONE
 * messages to the originating Qhorus channel.
 *
 * <p>Always returns 200 on processing failures — OpenClaw must not retry on our errors.
 * On dispatch failure, the case step hangs until Watchdog recovery. This trade-off is
 * revisited in openclaw#11 once OpenClaw's retry contract is verified.
 *
 * <p>Phase 1 speech act classification: always DONE. See openclaw#10 for graduation plan.
 * No auth — follows gateway topology (Claudony is the auth entry point). See
 * auth-retrofit-readiness.md protocol.
 */
@Path("/openclaw/delivery/channel")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class OpenClawDeliveryResource {

    private static final Logger log = Logger.getLogger(OpenClawDeliveryResource.class);

    @Inject
    ChannelService channelService;

    @Inject
    MessageService messageService;

    @POST
    @Path("/{channelId}")
    public Response deliver(@PathParam("channelId") String channelIdStr,
                             OpenClawDeliveryPayload payload) {
        UUID channelId;
        try {
            channelId = UUID.fromString(channelIdStr);
        } catch (IllegalArgumentException e) {
            return Response.status(400).build(); // malformed UUID — client error, not missing resource
        }

        if (channelService.findById(channelId).isEmpty()) {
            log.warnf("Delivery received for unknown channelId=%s", channelId);
            return Response.status(404).build();
        }

        try {
            messageService.dispatch(MessageDispatch.builder()
                    .channelId(channelId)
                    .sender(payload != null && payload.agentId() != null ? payload.agentId() : "openclaw-agent")
                    .type(MessageType.DONE)
                    .content(payload != null && payload.output() != null ? payload.output() : "")
                    .actorType(ActorType.AGENT)
                    .build());
        } catch (Exception e) {
            // Fail open — return 200 so OpenClaw does not retry. Case step hangs until Watchdog.
            // Once openclaw#11 resolves the retry contract, this can return 5xx.
            log.errorf("Failed to dispatch DONE to channel %s: %s", channelId, e.getMessage());
        }

        return Response.ok().build();
    }
}
