package io.casehub.openclaw.client;

import java.util.Optional;

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
        String baseUrl();
        Optional<String> token();
    }

    interface Agent {
        @WithDefault("claude-opus-4-5")
        String defaultModel();

        @WithDefault("120")
        int defaultTimeoutSeconds();
    }
}
