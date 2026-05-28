package io.casehub.openclaw.context;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.casehub.qhorus.api.gateway.MessageReceivedEvent;

/**
 * Manages per-channel ring buffers of recent Qhorus channel activity and
 * answers per-agent context queries.
 *
 * <p>{@link #associate} is called by {@code WorkerProvisioner} (Epic 4) when an
 * OpenClaw agent is provisioned for a case. Until then, {@link #query} returns
 * {@link WindowContent#noAssociation()} — the correct fail-open state.
 *
 * <p>{@link #add} is called by {@code ChannelContextWindowObserver} on every
 * dispatched Qhorus message. It is intentionally a no-op for unassociated channels.
 */
@ApplicationScoped
public class ChannelContextWindowService {

    private static final Logger log = Logger.getLogger(ChannelContextWindowService.class);

    @ConfigProperty(name = "casehub.openclaw.context-window.max-messages-per-channel",
                    defaultValue = "100")
    int maxMessagesPerChannel;

    @ConfigProperty(name = "casehub.openclaw.context-window.ttl", defaultValue = "PT30M")
    Duration ttl;

    private final AtomicLong windowSeq = new AtomicLong(0);
    private final ConcurrentHashMap<UUID, ChannelRingBuffer> buffers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<UUID>> agentChannels = new ConcurrentHashMap<>();

    /**
     * Registers channel associations for an agent. Additive — never removes channels.
     * Pre-creates ring buffers so {@link #add} can use a lock-free get().
     * Called by WorkerProvisioner (Epic 4).
     */
    public void associate(String agentId, Set<UUID> channelIds) {
        agentChannels.merge(agentId, Set.copyOf(channelIds), (existing, added) -> {
            Set<UUID> merged = new HashSet<>(existing);
            merged.addAll(added);
            return Collections.unmodifiableSet(merged);
        });
        channelIds.forEach(id ->
                buffers.computeIfAbsent(id, k -> new ChannelRingBuffer(maxMessagesPerChannel, ttl)));
    }

    /**
     * Ingests a Qhorus message into the ring buffer for its channel.
     * Silent no-op if the channel is not associated with any agent.
     * Called by ChannelContextWindowObserver.
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
     * <p>Returns {@link WindowContent#noAssociation()} if the agent has no registered
     * channels — the correct fail-open state before Epic 4 wires associate().
     */
    public WindowContent query(String agentId, long since) {
        Set<UUID> channelIds = agentChannels.get(agentId);
        if (channelIds == null || channelIds.isEmpty()) {
            return WindowContent.noAssociation();
        }

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

    /**
     * Eagerly evicts TTL-expired entries from all buffers.
     * Called by {@code EvictionScheduler} (app module) at the TTL interval.
     */
    public void evictExpired() {
        Instant now = Instant.now();
        buffers.values().forEach(b -> b.evictExpired(now));
    }
}
