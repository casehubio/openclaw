package io.casehub.openclaw.casehub;

import io.casehub.api.model.CaseChannel;
import io.casehub.openclaw.client.OpenClawClientConfig;
import io.casehub.openclaw.client.OpenClawHookClient;
import io.casehub.openclaw.client.OpenClawInvocationException;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.gateway.ChannelBackend;
import io.casehub.qhorus.api.gateway.ChannelInitialisedEvent;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.DeliveryGuarantee;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.UUID;

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
 * <p>When a COMMAND carries a correlationId (= commitmentId), a fully-resolved commitment
 * context block is appended to the message before invocation. The agent receives the agentId
 * and commitmentId as concrete values — no template variables to resolve (openclaw#28).
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
    public OpenClawChannelBackend(final OpenClawAgentRegistry registry,
                                   final OpenClawHookClient hookClient,
                                   final ChannelGateway gateway,
                                   final OpenClawClientConfig config) {
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
    public void open(final ChannelRef channel, final Map<String, String> metadata) {
        // Registration handled via ChannelInitialisedEvent — no-op here
    }

    @Override
    public void post(final ChannelRef channel, final OutboundMessage message) {
        if (message.type() != MessageType.COMMAND) {return;}

        final UUID caseId = extractCaseId(channel.name());
        if (caseId == null) {return;}

        final String agentId;
        if (message.target() != null && !message.target().isBlank()) {
            agentId = message.target();
            if (!registry.findCaseId(agentId).map(caseId::equals).orElse(false)) {
                if (registry.findCaseId(agentId).isPresent()) {
                    log.warnf("Target agent %s registered for caseId=%s, not %s — routing bug, COMMAND dropped",
                              agentId, registry.findCaseId(agentId).orElse(null), caseId);
                } else {
                    log.warnf("Target agent %s not registered — COMMAND dropped (agent may have crashed)", agentId);
                }
                return;
            }
        } else {
            agentId = registry.findAgentId(caseId).orElse(null);
        }
        if (agentId == null) {
            log.debugf("No OpenClaw agent for caseId=%s — ignoring COMMAND on %s", caseId, channel.name());
            return;
        }

        final String sessionKey = registry.findSessionKey(agentId).orElse(null);
        if (sessionKey == null) {
            log.warnf("No session key found for agentId=%s (registry write race?) — ignoring COMMAND", agentId);
            return;
        }

        String webhookUrl = config.delivery().baseUrl() + "/channel/" + channel.id();
        webhookUrl = appendDeliveryToken(webhookUrl);
        hookClient.registerSession(agentId, sessionKey, webhookUrl);

        try {
            final UUID correlationId = message.correlationId() != null
                                       ? UUID.fromString(message.correlationId())
                                       : null;
            hookClient.invoke(agentId, buildPrompt(message.content(), agentId, correlationId),
                              config.agent().defaultModel(), config.agent().defaultTimeoutSeconds());
            log.debugf("Invoked OpenClaw agent: agentId=%s caseId=%s", agentId, caseId);
        } catch (OpenClawInvocationException e) {
            log.errorf("OpenClaw invocation failed for agentId=%s: %s", agentId, e.getMessage());
        }}

    @Override
    public DeliveryGuarantee deliveryGuarantee() {
        return DeliveryGuarantee.AT_LEAST_ONCE;
    }

    @Override
    public void close(final ChannelRef channel) {
        // Qhorus channels are persistent — no teardown needed
    }

    private String appendDeliveryToken(String url) {
        return config.delivery().token()
                .filter(t -> !t.isBlank())
                .map(t -> url + "?token=" + t)
                .orElse(url);
    }

    /** Parses "case-{caseId}/{purpose}" → UUID, or returns null if format doesn't match. */
    UUID extractCaseId(final String channelName) {
        return CaseChannelNames.extractCaseId(channelName);
    }

    /**
     * Appends a fully-resolved commitment context block to the task content when a
     * commitmentId is available. Both agentId and commitmentId are substituted at build
     * time — the rendered text contains no template variables.
     *
     * <p>When correlationId is null (COMMAND without a commitment), content is returned as-is.
     */
    private String buildPrompt(final String content, final String agentId, final UUID correlationId) {
        if (correlationId == null) {
            return content;
        }
        final String id = correlationId.toString();
        return content + """

                ---
                CaseHub commitment active.
                  commitmentId: %1$s

                  Complete  → casehub_done("%2$s", "%1$s", outcome)
                  Decline   → casehub_reject("%2$s", "%1$s", reason)
                  Progress  → casehub_checkpoint("%2$s", "%1$s", note)
                  Escalate  → casehub_escalate("%2$s", "%1$s", reason, toAgent?)
                  Delegate  → casehub_delegate("%2$s", "%1$s", reason, toAgent)
                  Block     → casehub_block("%2$s", "%1$s", reason, blockedUntil)
                """.formatted(id, agentId);
    }
}
