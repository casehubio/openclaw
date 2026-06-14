package io.casehub.openclaw.casehub;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import io.casehub.api.model.CaseChannel;
import io.casehub.api.spi.CaseChannelProvider;
import io.casehub.openclaw.context.ChannelContextWindowService;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.casehub.qhorus.runtime.message.MessageService;

/**
 * Creates and manages Qhorus channels per CaseHub case.
 *
 * <p>Three normative channels per case: work, observe, oversight — all APPEND semantic,
 * matching Claudony's NormativeChannelLayout. Channel names follow the CaseChannel convention
 * "case-{caseId}/{purpose}".
 *
 * <p>openChannel() is idempotent: finds existing channel by name before creating.
 * gateway.initChannel() is called on new channels only — channels that already exist in the
 * DB were registered by the ChannelGateway startup hook (onStart recovers all persisted channels).
 * bindChannel() is called after each open to register with ChannelContextWindow.
 */
@ApplicationScoped
public class OpenClawCaseChannelProvider implements CaseChannelProvider {

    private static final Logger log = Logger.getLogger(OpenClawCaseChannelProvider.class);
    private static final String QHORUS_NAME_KEY = "qhorus-name";

    private final ChannelService channelService;
    private final MessageService messageService;
    private final ChannelContextWindowService contextService;
    private final ChannelGateway gateway;

    @Inject
    public OpenClawCaseChannelProvider(ChannelService channelService,
                                        MessageService messageService,
                                        ChannelContextWindowService contextService,
                                        ChannelGateway gateway) {
        this.channelService = channelService;
        this.messageService = messageService;
        this.contextService = contextService;
        this.gateway = gateway;
    }

    @Override
    public CaseChannel openChannel(UUID caseId, String purpose) {
        String channelName = CaseChannel.channelName(caseId, purpose);
        OpenClawNormativeLayout.ChannelSpec spec = OpenClawNormativeLayout.LAYOUT.get(purpose);
        String description = spec != null ? spec.description() : purpose;
        Set<MessageType> allowedSet = spec != null ? spec.allowedTypes() : null;
        Set<MessageType> deniedSet = spec != null ? spec.deniedTypes() : null;

        Channel channel = channelService.findByName(channelName)
                .orElseGet(() -> {
                    Channel created = channelService.create(new io.casehub.qhorus.runtime.channel.ChannelCreateRequest(
                            channelName, description, ChannelSemantic.APPEND,
                            null, null, null, null, null, allowedSet, deniedSet,
                            null, null, null, null));
                    gateway.initChannel(created.id, new ChannelRef(created.id, created.name));
                    return created;
                });

        contextService.bindChannel(caseId, channel.id);
        log.debugf("Opened channel: %s (id=%s)", channelName, channel.id);
        return new CaseChannel(channel.id.toString(), channel.name, purpose, "qhorus",
                Map.of(QHORUS_NAME_KEY, channel.name));
    }

    @Override
    public void postToChannel(CaseChannel channel, String from, String content,
                               MessageType type, String correlationId, String deadline) {
        // MessageDispatch builder requires type — default to STATUS when unspecified
        MessageType effectiveType = type != null ? type : MessageType.STATUS;
        messageService.dispatch(MessageDispatch.builder()
                .channelId(UUID.fromString(channel.id()))
                .sender(from)
                .type(effectiveType)
                .content(content)
                .correlationId(correlationId)
                .deadline(deadline != null ? Instant.parse(deadline) : null)
                .actorType(ActorType.AGENT)
                .build());
    }

    @Override
    public void closeChannel(CaseChannel channel) {
        // Qhorus channels are persistent — no close operation
    }

    @Override
    public List<CaseChannel> listChannels(UUID caseId) {
        String prefix = CaseChannel.CASE_CHANNEL_PREFIX + caseId + "/";
        return channelService.findByNamePrefix(prefix).stream()
                .map(ch -> new CaseChannel(
                        ch.id.toString(),
                        ch.name,
                        extractPurpose(ch.name, caseId),
                        "qhorus",
                        Map.of(QHORUS_NAME_KEY, ch.name)))
                .toList();
    }

    private String extractPurpose(String channelName, UUID caseId) {
        String prefix = CaseChannel.CASE_CHANNEL_PREFIX + caseId + "/";
        return channelName.startsWith(prefix) ? channelName.substring(prefix.length()) : channelName;
    }
}
