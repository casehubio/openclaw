package io.casehub.openclaw.app;

import jakarta.annotation.security.PermitAll;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import io.casehub.openclaw.casehub.DirectCallBridge;
import io.smallrye.common.annotation.Blocking;

import org.jboss.logging.Logger;

@PermitAll
@Blocking
@ApplicationScoped
@Path("/openclaw/direct-call")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class DirectCallDeliveryResource {

    private static final Logger log = Logger.getLogger(DirectCallDeliveryResource.class);

    @Inject
    DirectCallBridge bridge;

    @Inject
    DeliveryTokenValidator tokenValidator;

    @POST
    @Path("/{correlationId}")
    public Response deliver(@PathParam("correlationId") String correlationId,
                             @QueryParam("token") String token,
                             DirectCallDeliveryPayload payload) {
        if (!tokenValidator.isValid(token)) {
            return Response.status(403).build();
        }
        try {
            String output = payload != null && payload.output() != null
                    ? payload.output() : "";
            bridge.complete(correlationId, output);
        } catch (Exception e) {
            log.errorf("direct-call delivery failed for correlationId=%s: %s",
                    correlationId, e.getMessage());
        }
        return Response.ok().build();
    }
}
