package io.casehub.openclaw.client;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "casehub.openclaw")
public interface OpenClawClientConfig {

    Gateway gateway();

    Delivery delivery();

    Agent agent();

    interface Gateway {
        // SmallRye @ConfigMapping auto-converts bearerToken() → bearer-token
        String url();
        String bearerToken();
    }

    interface Delivery {
        // No consumers in this epic — placed here to establish the config boundary
        // for WorkerProvisioner (casehub/ module, later epic), which constructs:
        //   webhookUrl = baseUrl() + "/channel/" + qhorusChannelId
        String baseUrl();
    }

    interface Agent {
        @WithDefault("claude-opus-4-5")
        String defaultModel();

        @WithDefault("120")
        int defaultTimeoutSeconds();
    }
}
