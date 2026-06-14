package io.casehub.openclaw.casehub;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import io.casehub.api.model.CaseChannel;
import io.casehub.api.spi.ReactiveCaseChannelProvider;
import io.casehub.openclaw.context.ChannelContextWindowService;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.channel.ReactiveChannelService;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.casehub.qhorus.runtime.message.ReactiveMessageService;
import io.quarkus.arc.properties.IfBuildProperty;
import io.smallrye.mutiny.Uni;

/**
 * Reactive mirror of {@link OpenClawCaseChannelProvider} for Vert.x IO thread compatibility.
 *
 * <p>Uses a memoized layout cache ({@code ConcurrentHashMap + memoize().indefinitely()}) to
 * ensure channel initialization runs exactly once per caseId per process lifetime. This eliminates
 * the DB unique-constraint race when the engine triggers concurrent {@code openChannel()} calls
 * for the same case (e.g. work, observe, oversight in rapid succession).
 *
 * <p>On first touch for a caseId, {@code initializeLayout()} creates all three normative channels
 * (work / observe / oversight) in a sequential {@code flatMap} chain. Subsequent calls for the
 * same caseId return from the memoized cache with no DB round-trips.
 *
 * <p>{@code gateway.initChannel()} is called on newly created channels only — channels that already
 * exist in the DB were registered by {@code ChannelGateway.onStart()} at startup.
 *
 * <p>Activated when {@code casehub.qhorus.reactive.enabled=true}, which also activates
 * {@code ReactiveChannelService} and {@code ReactiveMessageService}.
 */
@IfBuildProperty(name = "casehub.qhorus.reactive.enabled", stringValue = "true")
@ApplicationScoped
public class ReactiveOpenClawCaseChannelProvider implements ReactiveCaseChannelProvider {

    private static final Logger log = Logger.getLogger(ReactiveOpenClawCaseChannelProvider.class);
    private static final String QHORUS_NAME_KEY = "qhorus-name";

    private final ReactiveChannelService channelService;
    private final ReactiveMessageService messageService;
    private final ChannelContextWindowService contextService;
    private final ChannelGateway gateway;

    // Per-caseId memoized layout. Eliminates DB race on concurrent openChannel() calls.
    // Failure path removes the entry so the next call can retry (onFailure.invoke removes).
    private final ConcurrentHashMap<UUID, Uni<Map<String, CaseChannel>>> layoutCache =
            new ConcurrentHashMap<>();

    @Inject
    public ReactiveOpenClawCaseChannelProvider(ReactiveChannelService channelService,
                                                ReactiveMessageService messageService,
                                                ChannelContextWindowService contextService,
                                                ChannelGateway gateway) {
        this.channelService = channelService;
        this.messageService = messageService;
        this.contextService = contextService;
        this.gateway = gateway;
    }

    @Override
    public Uni<CaseChannel> openChannel(UUID caseId, String purpose) {
        return layoutCache.computeIfAbsent(caseId, id ->
                        initializeLayout(id)
                                .onFailure().invoke(err -> layoutCache.remove(id))
                                .memoize().indefinitely())
                .map(channels -> {
                    CaseChannel ch = channels.get(purpose);
                    if (ch == null) {
                        throw new IllegalArgumentException(
                                "Channel purpose '" + purpose + "' not in layout for case " + caseId);
                    }
                    return ch;
                });
    }

    @Override
    public Uni<Void> postToChannel(CaseChannel channel, String from, String content,
                                    MessageType type, String correlationId, String deadline) {
        MessageType effectiveType = type != null ? type : MessageType.STATUS;
        return messageService.dispatch(MessageDispatch.builder()
                        .channelId(UUID.fromString(channel.id()))
                        .sender(from)
                        .type(effectiveType)
                        .content(content)
                        .correlationId(correlationId)
                        .deadline(deadline != null ? Instant.parse(deadline) : null)
                        .actorType(ActorType.AGENT)
                        .build())
                .replaceWithVoid();
    }

    @Override
    public Uni<Void> closeChannel(CaseChannel channel) {
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<List<CaseChannel>> listChannels(UUID caseId) {
        String prefix = CaseChannel.CASE_CHANNEL_PREFIX + caseId + "/";
        return channelService.findByNamePrefix(prefix)
                .map(channels -> channels.stream()
                        .map(ch -> new CaseChannel(
                                ch.id.toString(),
                                ch.name,
                                extractPurpose(ch.name, caseId),
                                "qhorus",
                                Map.of(QHORUS_NAME_KEY, ch.name)))
                        .toList());
    }

    // ── internals ────────────────────────────────────────────────────────────

    /**
     * Creates all layout channels for a case in sequence, registering each with the gateway
     * and context window. Called at most once per caseId per process lifetime (memoized).
     */
    private Uni<Map<String, CaseChannel>> initializeLayout(UUID caseId) {
        List<String> purposes = OpenClawNormativeLayout.LAYOUT.keySet().stream().sorted().toList();

        // Seed with empty accumulator; flatMap each purpose sequentially
        Uni<ConcurrentHashMap<String, CaseChannel>> seed =
                Uni.createFrom().item(new ConcurrentHashMap<>());

        return purposes.stream()
                .reduce(seed,
                        (uni, purpose) -> uni.flatMap(acc ->
                                openOrCreate(caseId, purpose)
                                        .map(ch -> { acc.put(purpose, ch); return acc; })),
                        (a, b) -> { throw new UnsupportedOperationException("parallel not supported"); })
                .map(acc -> (Map<String, CaseChannel>) acc);
    }

    /**
     * Finds an existing channel by name, or creates it and fires {@link ChannelGateway#initChannel}.
     * {@code contextService.bindChannel()} runs on all paths (find or create).
     */
    private Uni<CaseChannel> openOrCreate(UUID caseId, String purpose) {
        String channelName = CaseChannel.channelName(caseId, purpose);
        OpenClawNormativeLayout.ChannelSpec spec = OpenClawNormativeLayout.LAYOUT.get(purpose);
        String description = spec != null ? spec.description() : purpose;
        Set<MessageType> allowedSet = spec != null ? spec.allowedTypes() : null;
        Set<MessageType> deniedSet = spec != null ? spec.deniedTypes() : null;

        return channelService.findByName(channelName)
                .flatMap(opt -> opt.isPresent()
                        ? Uni.createFrom().item(opt.get())
                        : channelService.create(new io.casehub.qhorus.runtime.channel.ChannelCreateRequest(
                                channelName, description, ChannelSemantic.APPEND,
                                null, null, null, null, null, allowedSet, deniedSet,
                                null, null, null, null))
                                .invoke(ch -> gateway.initChannel(ch.id, new ChannelRef(ch.id, ch.name))))
                .invoke(ch -> contextService.bindChannel(caseId, ch.id))
                .map(ch -> {
                    log.debugf("Opened channel (reactive): %s (id=%s)", channelName, ch.id);
                    return new CaseChannel(ch.id.toString(), ch.name, purpose, "qhorus",
                            Map.of(QHORUS_NAME_KEY, ch.name));
                });
    }

    private String extractPurpose(String channelName, UUID caseId) {
        String prefix = CaseChannel.CASE_CHANNEL_PREFIX + caseId + "/";
        return channelName.startsWith(prefix) ? channelName.substring(prefix.length()) : channelName;
    }
}
