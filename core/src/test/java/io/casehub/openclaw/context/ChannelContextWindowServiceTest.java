package io.casehub.openclaw.context;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.gateway.MessageReceivedEvent;
import io.casehub.qhorus.api.message.MessageType;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelContextWindowServiceTest {

    ChannelContextWindowService service;

    @BeforeEach
    void setup() {
        service = new ChannelContextWindowService();
        service.maxMessagesPerChannel = 100;
        service.ttl = Duration.ofMinutes(30);
    }

    private MessageReceivedEvent event(UUID channelId, String channelName, MessageType type) {
        String content = (type == MessageType.EVENT) ? null : "content";
        return new MessageReceivedEvent(channelName, channelId, type, "sender", "corr-1", content);
    }

    @Test
    void query_unknownAgent_returnsNoAssociation() {
        WindowContent result = service.query("unknown-agent", 0L);
        assertThat(result.agentHasAssociation()).isFalse();
        assertThat(result.messages()).isEmpty();
    }

    @Test
    void add_unassociatedChannel_silentlyIgnored() {
        UUID channelId = UUID.randomUUID();
        service.add(event(channelId, "unregistered/work", MessageType.STATUS));
        assertThat(service.query("any-agent", 0L).agentHasAssociation()).isFalse();
    }

    @Test
    void associate_add_query_roundTrip() {
        UUID channelId = UUID.randomUUID();
        service.associate("agent-1", Set.of(channelId));
        service.add(event(channelId, "test/work", MessageType.COMMAND));

        WindowContent result = service.query("agent-1", 0L);
        assertThat(result.agentHasAssociation()).isTrue();
        assertThat(result.messages()).hasSize(1);
        assertThat(result.messages().get(0).messageType()).isEqualTo(MessageType.COMMAND);
    }

    @Test
    void query_withSince_filtersCorrectly() {
        UUID channelId = UUID.randomUUID();
        service.associate("agent-1", Set.of(channelId));
        service.add(event(channelId, "test/work", MessageType.COMMAND));
        service.add(event(channelId, "test/work", MessageType.STATUS));
        service.add(event(channelId, "test/work", MessageType.DONE));

        long firstSeq = service.query("agent-1", 0L).messages().get(0).windowSeq();

        WindowContent result = service.query("agent-1", firstSeq);
        assertThat(result.messages()).hasSize(2);
        assertThat(result.messages())
                .extracting(ContextMessage::messageType)
                .containsExactly(MessageType.STATUS, MessageType.DONE);
    }

    @Test
    void multipleChannels_mergedSortedByWindowSeq() {
        UUID ch1 = UUID.randomUUID();
        UUID ch2 = UUID.randomUUID();
        service.associate("agent-1", Set.of(ch1, ch2));

        service.add(event(ch1, "work", MessageType.COMMAND));
        service.add(event(ch2, "observe", MessageType.STATUS));
        service.add(event(ch1, "work", MessageType.DONE));

        WindowContent result = service.query("agent-1", 0L);
        assertThat(result.messages()).hasSize(3);

        List<Long> seqs = result.messages().stream()
                .map(ContextMessage::windowSeq).toList();
        assertThat(seqs).isSorted();
    }

    @Test
    void associate_twice_sameAgent_channelsAddedNotReplaced() {
        UUID ch1 = UUID.randomUUID();
        UUID ch2 = UUID.randomUUID();
        service.associate("agent-1", Set.of(ch1));
        service.associate("agent-1", Set.of(ch2));

        service.add(event(ch1, "work", MessageType.STATUS));
        service.add(event(ch2, "observe", MessageType.STATUS));

        assertThat(service.query("agent-1", 0L).messages()).hasSize(2);
    }

    @Test
    void currentWindowSeq_reflectsGlobalCounter() {
        UUID channelId = UUID.randomUUID();
        service.associate("agent-1", Set.of(channelId));
        service.add(event(channelId, "work", MessageType.STATUS));
        service.add(event(channelId, "work", MessageType.STATUS));

        WindowContent result = service.query("agent-1", 0L);
        assertThat(result.currentWindowSeq()).isEqualTo(2L);
    }

    @Test
    void lastWindowSeq_isMaxReturnedSeq() {
        UUID channelId = UUID.randomUUID();
        service.associate("agent-1", Set.of(channelId));
        service.add(event(channelId, "work", MessageType.STATUS)); // seq=1
        service.add(event(channelId, "work", MessageType.STATUS)); // seq=2

        WindowContent result = service.query("agent-1", 0L);
        assertThat(result.lastWindowSeq()).isEqualTo(2L);
    }

    @Test
    void lastWindowSeq_isSince_whenNoMessagesReturned() {
        UUID channelId = UUID.randomUUID();
        service.associate("agent-1", Set.of(channelId));
        service.add(event(channelId, "work", MessageType.STATUS)); // seq=1

        WindowContent result = service.query("agent-1", 1L);
        assertThat(result.messages()).isEmpty();
        assertThat(result.lastWindowSeq()).isEqualTo(1L);
    }

    @Test
    void restartDetection_staleClientCursor() {
        UUID channelId = UUID.randomUUID();
        service.associate("agent-1", Set.of(channelId));
        service.add(event(channelId, "work", MessageType.STATUS)); // seq=1
        service.add(event(channelId, "work", MessageType.STATUS)); // seq=2

        WindowContent result = service.query("agent-1", 50L);
        assertThat(result.currentWindowSeq()).isEqualTo(2L);
        assertThat(result.messages()).isEmpty();
        assertThat(result.agentHasAssociation()).isTrue();
    }

    @Test
    void windowSeq_isGloballyMonotonic_acrossChannels() {
        UUID ch1 = UUID.randomUUID();
        UUID ch2 = UUID.randomUUID();
        service.associate("agent-1", Set.of(ch1, ch2));

        for (int i = 0; i < 5; i++) {
            service.add(event(i % 2 == 0 ? ch1 : ch2, "chan", MessageType.STATUS));
        }

        List<Long> seqs = service.query("agent-1", 0L).messages().stream()
                .map(ContextMessage::windowSeq).toList();
        for (int i = 1; i < seqs.size(); i++) {
            assertThat(seqs.get(i)).isGreaterThan(seqs.get(i - 1));
        }
    }

    @Test
    void lastEvictionWindowSeq_reflectsOverflow() {
        service.maxMessagesPerChannel = 2;
        UUID channelId = UUID.randomUUID();
        service.associate("agent-1", Set.of(channelId));

        service.add(event(channelId, "work", MessageType.COMMAND)); // seq=1
        service.add(event(channelId, "work", MessageType.STATUS));  // seq=2
        service.add(event(channelId, "work", MessageType.DONE));    // seq=3 → evicts seq=1

        WindowContent result = service.query("agent-1", 0L);
        assertThat(result.lastEvictionWindowSeq()).isEqualTo(1L);
    }

    @Test
    void lastChannelActivity_isEpoch_whenNoMessages() {
        UUID channelId = UUID.randomUUID();
        service.associate("agent-1", Set.of(channelId));

        WindowContent result = service.query("agent-1", 0L);
        assertThat(result.lastChannelActivity()).isEqualTo(Instant.EPOCH);
    }

    @Test
    void concurrency_noExceptionAndBounded() throws InterruptedException {
        service.maxMessagesPerChannel = 10;
        UUID channelId = UUID.randomUUID();
        service.associate("agent-1", Set.of(channelId));

        int threads = 10;
        int messagesPerThread = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < messagesPerThread; j++) {
                        service.add(event(channelId, "work", MessageType.STATUS));
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        WindowContent result = service.query("agent-1", 0L);
        assertThat(result.messages()).hasSizeLessThanOrEqualTo(10);
        assertThat(result.agentHasAssociation()).isTrue();
    }
}
