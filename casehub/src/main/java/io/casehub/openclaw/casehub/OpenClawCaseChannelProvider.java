package io.casehub.openclaw.casehub;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import io.casehub.api.model.CaseChannel;
import io.casehub.api.spi.CaseChannelProvider;
import io.casehub.openclaw.context.ChannelContextWindowService;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.message.MessageService;

/**
 * Creates and manages Qhorus channels per CaseHub case.
 *
 * <p>Three normative channels per case: work, observe, oversight — all APPEND semantic,
 * matching Claudony's NormativeChannelLayout. Channel names follow the CaseChannel convention
 * "case-{caseId}/{purpose}".
 *
 * <p>openChannel() is idempotent: finds existing channel by name before creating.
 * bindChannel() is called after each open to register with ChannelContextWindow.
 */
@ApplicationScoped
public class OpenClawCaseChannelProvider implements CaseChannelProvider {

    private static final Logger log = Logger.getLogger(OpenClawCaseChannelProvider.class);
    private static final String QHORUS_NAME_KEY = "qhorus-name";

    // Normative layout: purpose → [description, allowedTypes CSV or null]
    // Source of truth: Claudony's NormativeChannelLayout (casehub/src/main/.../NormativeChannelLayout.java).
    // The spec §7.1 table differs (observe=EVENT+QUERY+STATUS, oversight=COMMAND+RESPONSE) — Claudony's
    // actual implementation is used here as the platform ground truth. Consolidation: parent#93.
    private static final Map<String, String[]> LAYOUT = Map.of(
            "work",     new String[]{"Primary coordination — all obligation-carrying message types", null},
            "observe",  new String[]{"Telemetry — EVENT only, no obligations created", "EVENT"},
            "oversight",new String[]{"Human governance — agent QUERY and human COMMAND", "COMMAND,QUERY"}
    );

    private final ChannelService channelService;
    private final MessageService messageService;
    private final ChannelContextWindowService contextService;

    @Inject
    public OpenClawCaseChannelProvider(ChannelService channelService,
                                        MessageService messageService,
                                        ChannelContextWindowService contextService) {
        this.channelService = channelService;
        this.messageService = messageService;
        this.contextService = contextService;
    }

    @Override
    public CaseChannel openChannel(UUID caseId, String purpose) {
        String channelName = CaseChannel.channelName(caseId, purpose);
        String[] spec = LAYOUT.get(purpose);
        String description = spec != null ? spec[0] : purpose;
        String allowedTypes = spec != null ? spec[1] : null;

        // Find existing before creating — idempotency contract from engine#323
        Channel channel = channelService.findByName(channelName)
                .orElseGet(() -> channelService.create(channelName, description,
                        ChannelSemantic.APPEND, null, null, null, null, null, allowedTypes));

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
