package io.casehub.openclaw.client;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;

@ApplicationScoped
public class BearerTokenRequestFilter implements ClientRequestFilter {

    private final String token;

    @Inject
    public BearerTokenRequestFilter(OpenClawClientConfig config) {
        this.token = config.gateway().bearerToken();
    }

    BearerTokenRequestFilter(String token) {
        this.token = token;
    }

    @Override
    public void filter(ClientRequestContext requestContext) {
        requestContext.getHeaders().putSingle("Authorization", "Bearer " + token);
    }
}
