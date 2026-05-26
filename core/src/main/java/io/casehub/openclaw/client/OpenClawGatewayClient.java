package io.casehub.openclaw.client;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@RegisterRestClient(configKey = "openclaw-gateway")
@RegisterProvider(BearerTokenRequestFilter.class)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface OpenClawGatewayClient {

    @POST
    @Path("/hooks/agent")
    Response invokeAgent(AgentInvocationRequest request);

    @POST
    @Path("/hooks/wake")
    Response wakeAgent(AgentWakeRequest request);
}
