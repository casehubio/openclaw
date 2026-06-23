package io.casehub.openclaw.app;

import java.util.UUID;

import jakarta.annotation.security.PermitAll;
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

/**
 * Receives human responses to oversight gate questions, delivered by OpenClaw
 * after the human replies on WhatsApp, Telegram, or another messaging platform.
 *
 * <p>Stays thin: validates gateId, delegates to {@link OversightGateService#fulfill(UUID, String)}.
 * Always returns 200 — OpenClaw must not retry oversight deliveries.
 * @PermitAll: OpenClaw callbacks carry no casehub OIDC token. See openclaw#41 spec §2.
 */
@PermitAll
@Path("/openclaw/delivery/oversight")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class OpenClawOversightDeliveryResource {

    private static final Logger log = Logger.getLogger(OpenClawOversightDeliveryResource.class);

    @Inject
    OversightGateService oversightGateService;

    @POST
    @Path("/{gateId}")
    public Response deliver(@PathParam("gateId") String gateIdStr,
                             OpenClawOversightDeliveryPayload payload) {
        UUID gateId;
        try {
            gateId = UUID.fromString(gateIdStr);
        } catch (IllegalArgumentException e) {
            return Response.status(400).build();
        }

        String rawOutput = payload != null ? payload.output() : null;
        try {
            oversightGateService.fulfill(gateId, rawOutput);
        } catch (Exception e) {
            log.errorf("Unexpected error in fulfill() for gateId=%s: %s", gateId, e.getMessage());
            // Fall through — return 200 so OpenClaw does not retry
        }
        return Response.ok().build();
    }
}
