package io.casehub.openclaw.context;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Per-channel bounded ring buffer of {@link ContextMessage} entries.
 * Package-private — accessed only by {@link ChannelContextWindowService}.
 *
 * <p>All methods are synchronized on {@code this}. Contention is per-channel;
 * concurrent writes to different channels proceed without blocking each other.
 */
final class ChannelRingBuffer {

    private final int maxSize;
    private final Duration ttl;
    private final Deque<ContextMessage> messages = new ArrayDeque<>();

    private long lastEvictionWindowSeq = -1L;
    private Instant lastActivity = Instant.EPOCH;

    ChannelRingBuffer(int maxSize, Duration ttl) {
        this.maxSize = maxSize;
        this.ttl = ttl;
    }

    synchronized void add(ContextMessage message) {
        if (messages.size() >= maxSize) {
            ContextMessage evicted = messages.pollFirst();
            if (evicted != null) {
                lastEvictionWindowSeq = evicted.windowSeq();
            }
        }
        messages.addLast(message);
        lastActivity = message.receivedAt();
    }

    synchronized List<ContextMessage> query(long since, Instant now) {
        Instant ttlBoundary = now.minus(ttl);
        return messages.stream()
                .filter(m -> m.windowSeq() > since)
                .filter(m -> !m.receivedAt().isBefore(ttlBoundary))
                .toList();
    }

    synchronized void evictExpired(Instant now) {
        Instant ttlBoundary = now.minus(ttl);
        messages.removeIf(m -> m.receivedAt().isBefore(ttlBoundary));
    }

    synchronized long lastEvictionWindowSeq() {
        return lastEvictionWindowSeq;
    }

    synchronized Instant lastActivity() {
        return lastActivity;
    }
}
