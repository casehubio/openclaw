package io.casehub.openclaw.context;

import java.time.Instant;
import java.util.UUID;

import io.casehub.qhorus.api.message.MessageType;

/**
 * One message entry in a ChannelContextWindow ring buffer.
 *
 * {@code windowSeq} is a global monotonic counter assigned by
 * {@link ChannelContextWindowService} — not Qhorus's per-channel sequenceNumber.
 *
 * {@code receivedAt} is stamped at ingestion time ({@code Instant.now()}) inside
 * {@link ChannelContextWindowService#add} — not taken from Qhorus (MessageReceivedEvent
 * carries no timestamp). Used only for TTL eviction; {@code windowSeq} governs ordering.
 *
 * {@code correlationId} is nullable: COMMAND and QUERY originate correlations; other
 * types carry the correlation they were issued with; EVENT has none.
 */
public record ContextMessage(
        long windowSeq,
        UUID channelId,
        String channelName,
        MessageType messageType,
        String senderId,
        String correlationId,
        String content,
        Instant receivedAt) {}
