package io.casehub.openclaw.casehub;

import java.util.Map;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import io.casehub.api.model.CaseChannel;
import io.casehub.openclaw.client.OpenClawClientConfig;
import io.casehub.openclaw.client.OpenClawHookClient;
import io.casehub.openclaw.client.OpenClawInvocationException;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.gateway.ChannelBackend;
import io.casehub.qhorus.api.gateway.ChannelInitialisedEvent;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;

/**
 * Bridges Qhorus COMMANDs to OpenClaw agents via the hook API.
 *
 * <p>Self-registers with ChannelGateway by observing ChannelInitialisedEvent for case channels.
 * This also handles startup recovery: Qhorus fires ChannelInitialisedEvent for all persisted
 * channels at startup, so the backend re-registers without its own recovery logic.
 *
 * <p>Only COMMAND messages invoke the agent. All other types are silently ignored — they are
 * already stored in the ring buffer by ChannelContextWindowObserver and in the Qhorus ledger.
 *
 * <p>OpenClawInvocationException is caught and logged — ChannelGateway.fanOut() is non-fatal
 * for non-default backends.
 */
@ApplicationScoped
public class OpenClawChannelBackend implements ChannelBackend {

    private static final Logger log = Logger.getLogger(OpenClawChannelBackend.class);

    private final OpenClawAgentRegistry registry;
    private final OpenClawHookClient hookClient;
    private final ChannelGateway gateway;
    private final OpenClawClientConfig config;

    @Inject
    public OpenClawChannelBackend(OpenClawAgentRegistry registry,
                                   OpenClawHookClient hookClient,
                                   ChannelGateway gateway,
                                   OpenClawClientConfig config) {
        this.registry = registry;
        this.hookClient = hookClient;
        this.gateway = gateway;
        this.config = config;
    }

    void onChannelInitialised(@Observes ChannelInitialisedEvent event) {
        if (!event.channelName().startsWith(CaseChannel.CASE_CHANNEL_PREFIX)) return;
        gateway.registerBackend(event.channelId(), this, "agent");
        log.debugf("Registered OpenClaw backend for channel: %s", event.channelName());
    }

    @Override
    public String backendId() {
        return "openclaw";
    }

    @Override
    public ActorType actorType() {
        return ActorType.AGENT;
    }

    @Override
    public void open(ChannelRef channel, Map<String, String> metadata) {
        // Registration handled via ChannelInitialisedEvent — no-op here
    }

    @Override
    public void post(ChannelRef channel, OutboundMessage message) {
        if (message.type() != MessageType.COMMAND) return;

        UUID caseId = extractCaseId(channel.name());
        if (caseId == null) return;

        String agentId = registry.findAgentId(caseId).orElse(null);
        if (agentId == null) {
            log.debugf("No OpenClaw agent for caseId=%s — ignoring COMMAND on %s", caseId, channel.name());
            return;
        }

        // Log-and-return rather than throw — post() must never propagate exceptions through fanOut()
        String sessionKey = registry.findSessionKey(agentId).orElse(null);
        if (sessionKey == null) {
            log.warnf("No session key found for agentId=%s (registry write race?) — ignoring COMMAND", agentId);
            return;
        }

        // webhookUrl is embedded in the POST /hooks/agent body — OpenClaw uses this
        // request-body URL for delivery. Concurrent overwrites of the session entry
        // are safe because invoke() sends the URL it reads at call time.
        String webhookUrl = config.delivery().baseUrl() + "/channel/" + channel.id();
        hookClient.registerSession(agentId, sessionKey, webhookUrl);

        try {
            hookClient.invoke(agentId, message.content(),
                    config.agent().defaultModel(), config.agent().defaultTimeoutSeconds());
            log.debugf("Invoked OpenClaw agent: agentId=%s caseId=%s", agentId, caseId);
        } catch (OpenClawInvocationException e) {
            log.errorf("OpenClaw invocation failed for agentId=%s: %s", agentId, e.getMessage());
            // Non-fatal — ChannelGateway.fanOut() absorbs exceptions from non-default backends
        }
    }

    @Override
    public void close(ChannelRef channel) {
        // Qhorus channels are persistent — no teardown needed
    }

    /** Parses "case-{caseId}/{purpose}" → UUID, or returns null if format doesn't match. */
    UUID extractCaseId(String channelName) {
        return CaseChannelNames.extractCaseId(channelName);
    }
}
