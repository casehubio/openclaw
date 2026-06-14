package io.casehub.openclaw.context;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import io.casehub.qhorus.api.gateway.MessageReceivedEvent;

/**
 * Manages per-channel ring buffers of recent Qhorus channel activity and
 * answers per-agent context queries.
 *
 * <p>Association is two-phase and ordering-independent:
 * <ul>
 *   <li>{@link #bindAgent} registers which case an agent handles.
 *   <li>{@link #bindChannel} registers which channels belong to a case and
 *       initialises the ring buffer so {@link #add} can proceed immediately.
 *   <li>{@link #query} joins the two maps at read time — no coordination needed between callers.
 * </ul>
 *
 * <p>{@link #unbindAgent} is called by OpenClawWorkerStatusListener on worker completion
 * to release the agentToCase entry. The caseChannels entry is retained until TTL eviction.
 */
@ApplicationScoped
public class ChannelContextWindowService {

    @ConfigProperty(name = "casehub.openclaw.context-window.max-messages-per-channel",
                    defaultValue = "100")
    int maxMessagesPerChannel;

    @ConfigProperty(name = "casehub.openclaw.context-window.ttl", defaultValue = "PT30M")
    Duration ttl;

    private final AtomicLong windowSeq = new AtomicLong(0);
    private final ConcurrentHashMap<UUID, ChannelRingBuffer> buffers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UUID> agentToCase = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Set<UUID>> caseChannels = new ConcurrentHashMap<>();

    /**
     * Registers which case this agent handles. Called by OpenClawWorkerProvisioner.provision().
     * Ordering-independent with bindChannel() — the service joins at query time.
     * Last-write-wins: a second call for the same agentId overwrites the first.
     */
    public void bindAgent(String agentId, UUID caseId) {
        agentToCase.put(agentId, caseId);
    }

    /**
     * Registers a channel under a case and initialises its ring buffer.
     * Called by OpenClawCaseChannelProvider.openChannel().
     * Idempotent — safe to call multiple times for the same (caseId, channelId).
     */
    public void bindChannel(UUID caseId, UUID channelId) {
        caseChannels.computeIfAbsent(caseId, id -> ConcurrentHashMap.newKeySet()).add(channelId);
        buffers.putIfAbsent(channelId, new ChannelRingBuffer(maxMessagesPerChannel, ttl));
    }

    /**
     * Removes the agent's case association. Called by OpenClawWorkerStatusListener.onWorkerCompleted().
     * The caseChannels entry is retained briefly — call closeCase() when the case is fully closed.
     */
    public void unbindAgent(String agentId) {
        agentToCase.remove(agentId);
    }

    /**
     * Removes all channel associations and ring buffers for a closed case.
     * Called by OpenClawWorkerStatusListener.onWorkerCompleted() after unbindAgent().
     *
     * <p>Any late messages arriving after closeCase() silently no-op in add() — the buffer
     * is gone, add() guards on null and returns immediately. This is the correct behaviour:
     * messages arriving after case close are unobservable and should be discarded.
     */
    public void closeCase(UUID caseId) {
        Set<UUID> channelIds = caseChannels.remove(caseId);
        if (channelIds != null) {
            channelIds.forEach(buffers::remove);
        }
    }

    /**
     * Ingests a Qhorus message into the ring buffer for its channel.
     * Silent no-op if bindChannel() has not been called for this channelId.
     * Called by ChannelContextWindowObserver on every dispatched Qhorus message.
     */
    public void add(MessageReceivedEvent event) {
        ChannelRingBuffer buffer = buffers.get(event.channelId());
        if (buffer == null) return;
        buffer.add(new ContextMessage(
                windowSeq.incrementAndGet(),
                event.channelId(),
                event.channelName(),
                event.messageType(),
                event.senderId(),
                event.correlationId(),
                event.content(),
                Instant.now()));
    }

    /**
     * Returns buffered channel context for the given agent since the cursor.
     *
     * <p>Returns {@link WindowContent#noAssociation()} if bindAgent() has not been called.
     * Returns an associated empty window if bindAgent() was called but no channels are
     * bound yet via bindChannel() — this is a transient state in the normal engine flow.
     */
    public WindowContent query(String agentId, long since) {
        UUID caseId = agentToCase.get(agentId);
        if (caseId == null) return WindowContent.noAssociation();

        Set<UUID> channelIds = caseChannels.getOrDefault(caseId, Set.of());
        Instant now = Instant.now();
        List<ContextMessage> merged = new ArrayList<>();
        long maxEvictionSeq = -1L;
        Instant latestActivity = Instant.EPOCH;

        for (UUID channelId : channelIds) {
            ChannelRingBuffer buffer = buffers.get(channelId);
            if (buffer == null) continue;
            merged.addAll(buffer.query(since, now));
            long evSeq = buffer.lastEvictionWindowSeq();
            if (evSeq > maxEvictionSeq) maxEvictionSeq = evSeq;
            Instant la = buffer.lastActivity();
            if (la.isAfter(latestActivity)) latestActivity = la;
        }

        merged.sort(Comparator.comparingLong(ContextMessage::windowSeq));
        long lastSeq = merged.isEmpty() ? since : merged.getLast().windowSeq();
        long current = windowSeq.get();
        return new WindowContent(merged, maxEvictionSeq, lastSeq, current, true, latestActivity);
    }

    /** Eagerly evicts TTL-expired entries from all buffers. Called by EvictionScheduler. */
    public void evictExpired() {
        Instant now = Instant.now();
        buffers.values().forEach(b -> b.evictExpired(now));
    }
}
