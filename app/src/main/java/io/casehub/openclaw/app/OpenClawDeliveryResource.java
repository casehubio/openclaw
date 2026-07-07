package io.casehub.openclaw.app;

import java.util.Optional;
import java.util.UUID;

import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.jboss.logging.Logger;

import io.casehub.openclaw.casehub.OversightGateService;
import io.casehub.engine.common.qualifier.CrossTenant;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.store.CrossTenantChannelStore;

/**
 * Receives OpenClaw agent results delivered via deliver:webhook.
 *
 * <p>Stays thin: validates channelId, resolves tenancyId cross-tenant, delegates to
 * {@link OversightGateService#evaluate(UUID, String, String, String)} which archives the agent
 * output as a non-resolving STATUS message on the work channel.
 * Always returns 200 — OpenClaw must not retry.
 *
 * <p>Uses {@link CrossTenantChannelStore} rather than tenant-scoped {@link io.casehub.qhorus.runtime.channel.ChannelService}
 * because the delivery webhook carries no casehub principal — the channel lookup must be
 * cross-tenant or it will return empty for any non-default tenant (openclaw#29).
 *
 * <p>Completion signaling (DONE, DECLINE, etc.) is owned by MCP tool calls
 * ({@code casehub_done}, {@code casehub_reject}, etc.) which dispatch typed Qhorus
 * messages during the agent turn (openclaw#28). @PermitAll: OpenClaw webhook callbacks
 * carry no casehub OIDC token — auth is structurally impossible at this call site.
 * See openclaw#41 spec §2 and auth-retrofit-readiness.md protocol.
 */
@PermitAll
@Path("/openclaw/delivery/channel")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class OpenClawDeliveryResource {

    private static final Logger log = Logger.getLogger(OpenClawDeliveryResource.class);

    @Inject
    @CrossTenant
    CrossTenantChannelStore crossTenantChannelStore;

    @Inject
    OversightGateService oversightGateService;

    @Inject
    DeliveryTokenValidator tokenValidator;

    @POST
    @Path("/{channelId}")
    public Response deliver(@PathParam("channelId") String channelIdStr,
                             @QueryParam("token") String token,
                             OpenClawDeliveryPayload payload) {
        if (!tokenValidator.isValid(token)) {
            return Response.status(403).build();
        }
        UUID channelId;
        try {
            channelId = UUID.fromString(channelIdStr);
        } catch (IllegalArgumentException e) {
            return Response.status(400).build();
        }

        Optional<Channel> channel = crossTenantChannelStore.findById(channelId);
        if (channel.isEmpty()) {
            log.warnf("Delivery received for unknown channelId=%s — tenancyId unresolvable; skipping dispatch",
                    channelId);
        }
        String tenancyId = channel.map(ch -> ch.tenancyId()).orElse(null);

        String agentId = payload != null && payload.agentId() != null ? payload.agentId() : "openclaw-agent";
        String output  = payload != null && payload.output()  != null ? payload.output()  : "";

        oversightGateService.evaluate(channelId, tenancyId, agentId, output);
        return Response.ok().build();   // Always 200 — OpenClaw must not retry
    }
}
