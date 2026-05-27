package io.casehub.openclaw.context;

import java.time.Instant;
import java.util.List;

/**
 * Query result returned by {@link ChannelContextWindowService#query}.
 *
 * <p><b>Python SDK usage:</b>
 * <ol>
 *   <li>{@code agentHasAssociation = false} → skip injection silently (not yet wired)</li>
 *   <li>{@code since > currentWindowSeq} → service restarted; SDK resets cursor to 0
 *       and skips this turn</li>
 *   <li>{@code lastEvictionWindowSeq > since} → inject overflow notice (additive with messages)</li>
 *   <li>{@code messages} non-empty → format and inject as context</li>
 *   <li>{@code messages} empty AND {@code lastChannelActivity} older than TTL → inject idle notice</li>
 * </ol>
 *
 * <p><b>Overflow and messages are additive</b> — both injected when overflow occurred but
 * newer messages still exist. Never use if/elif to choose between them.
 *
 * @param lastEvictionWindowSeq  {@code -1} if no eviction has occurred; otherwise the
 *                               {@code windowSeq} of the most recently evicted message
 *                               across all associated channels
 * @param lastWindowSeq          max windowSeq of returned messages; {@code since} if none returned
 * @param currentWindowSeq       service's {@code AtomicLong} value at query time — used by
 *                               SDK to detect a service restart (since > currentWindowSeq)
 * @param lastChannelActivity    {@code Instant.EPOCH} if no messages ever received on any
 *                               associated channel; never null
 */
public record WindowContent(
        List<ContextMessage> messages,
        long lastEvictionWindowSeq,
        long lastWindowSeq,
        long currentWindowSeq,
        boolean agentHasAssociation,
        Instant lastChannelActivity) {

    public static WindowContent noAssociation() {
        return new WindowContent(List.of(), -1L, 0L, 0L, false, Instant.EPOCH);
    }
}
