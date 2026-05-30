package io.casehub.openclaw.casehub;

import java.util.List;
import java.util.Map;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Agent capability configuration for CaseHub SPI implementations.
 * Separate from OpenClawClientConfig (core/) which owns gateway/delivery/agent-defaults.
 *
 * <p>Example properties:
 * <pre>
 * casehub.openclaw.agents.finance-agent.capabilities=finance,banking
 * casehub.openclaw.agents.finance-agent.session-key=finance-agent
 * casehub.openclaw.agents.code-review-agent.capabilities=code-review
 * casehub.openclaw.agents.code-review-agent.session-key=cr-agent-main
 * </pre>
 */
@ConfigMapping(prefix = "casehub.openclaw")
public interface OpenClawCasehubConfig {

    /** Map of agentId → agent configuration. Keys are agentId strings (e.g. "finance-agent"). */
    Map<String, AgentEntry> agents();

    /** Oversight gate configuration. All properties optional (Phase 1: gate never fires). */
    Oversight oversight();

    interface AgentEntry {
        /** Capability tags this agent can handle (e.g. ["finance", "banking"]). */
        List<String> capabilities();

        /**
         * OpenClaw session name — passed as {@code sessionName} in /hooks/agent.
         * May differ from the map key if the OpenClaw session has a different name.
         * WARNING: field name (camelCase vs snake_case) unverified against live API — openclaw#11.
         */
        String sessionKey();
    }

    interface Oversight {
        /**
         * Agent used to deliver oversight gate questions to humans via messaging.
         * Defaults to the work agent if blank — acceptable for Phase 1 since gate never fires.
         * Phase 2: configure a dedicated messaging agent (e.g. "home-messaging-agent").
         *
         * Property: casehub.openclaw.oversight.agent-id
         */
        @WithDefault("")
        String agentId();
    }
}
