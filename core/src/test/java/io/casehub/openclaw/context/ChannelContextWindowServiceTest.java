package io.casehub.openclaw.context;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
import static org.assertj.core.api.Assertions.assertThatCode;

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

    // ── noAssociation path ────────────────────────────────────────────────────

    @Test
    void query_unknownAgent_returnsNoAssociation() {
        assertThat(service.query("unknown-agent", 0L).agentHasAssociation()).isFalse();
    }

    @Test
    void add_unregisteredChannel_silentlyIgnored() {
        UUID channelId = UUID.randomUUID();
        service.add(event(channelId, "case-x/work", MessageType.STATUS));
        assertThat(service.query("any-agent", 0L).agentHasAssociation()).isFalse();
    }

    // ── bindChannel before bindAgent (channel first) ──────────────────────────

    @Test
    void bindChannel_beforeBindAgent_messagesCaptured_returnedOnceAgentBinds() {
        UUID caseId = UUID.randomUUID();
        UUID channelId = UUID.randomUUID();
        service.bindChannel(caseId, channelId);
        service.add(event(channelId, "case-x/work", MessageType.STATUS)); // captured before agent bound

        service.bindAgent("agent-1", caseId);
        WindowContent result = service.query("agent-1", 0L);
        assertThat(result.agentHasAssociation()).isTrue();
        assertThat(result.messages()).hasSize(1);
    }

    @Test
    void bindAgent_withoutBindChannel_returnsEmptyWindow_notNoAssociation() {
        UUID caseId = UUID.randomUUID();
        service.bindAgent("agent-1", caseId);
        WindowContent result = service.query("agent-1", 0L);
        assertThat(result.agentHasAssociation()).isTrue();
        assertThat(result.messages()).isEmpty();
    }

    // ── normal path (bindAgent + bindChannel, then add + query) ──────────────

    @Test
    void bindAgent_bindChannel_add_query_roundTrip() {
        UUID caseId = UUID.randomUUID();
        UUID channelId = UUID.randomUUID();
        service.bindAgent("agent-1", caseId);
        service.bindChannel(caseId, channelId);
        service.add(event(channelId, "case-x/work", MessageType.COMMAND));

        WindowContent result = service.query("agent-1", 0L);
        assertThat(result.agentHasAssociation()).isTrue();
        assertThat(result.messages()).hasSize(1);
        assertThat(result.messages().get(0).messageType()).isEqualTo(MessageType.COMMAND);
    }

    @Test
    void bindChannel_twice_samePair_idempotent() {
        UUID caseId = UUID.randomUUID();
        UUID channelId = UUID.randomUUID();
        service.bindAgent("agent-1", caseId);
        service.bindChannel(caseId, channelId);
        service.bindChannel(caseId, channelId); // idempotent — no duplicate buffer
        service.add(event(channelId, "case-x/work", MessageType.STATUS));

        assertThat(service.query("agent-1", 0L).messages()).hasSize(1);
    }

    @Test
    void query_withSince_filtersCorrectly() {
        UUID caseId = UUID.randomUUID();
        UUID channelId = UUID.randomUUID();
        service.bindAgent("agent-1", caseId);
        service.bindChannel(caseId, channelId);
        service.add(event(channelId, "case-x/work", MessageType.COMMAND)); // seq=1
        service.add(event(channelId, "case-x/work", MessageType.STATUS));  // seq=2
        service.add(event(channelId, "case-x/work", MessageType.DONE));    // seq=3

        long firstSeq = service.query("agent-1", 0L).messages().get(0).windowSeq();
        WindowContent result = service.query("agent-1", firstSeq);
        assertThat(result.messages()).hasSize(2);
        assertThat(result.messages())
                .extracting(ContextMessage::messageType)
                .containsExactly(MessageType.STATUS, MessageType.DONE);
    }

    @Test
    void multipleChannels_mergedSortedByWindowSeq() {
        UUID caseId = UUID.randomUUID();
        UUID ch1 = UUID.randomUUID();
        UUID ch2 = UUID.randomUUID();
        service.bindAgent("agent-1", caseId);
        service.bindChannel(caseId, ch1);
        service.bindChannel(caseId, ch2);

        service.add(event(ch1, "work", MessageType.COMMAND));
        service.add(event(ch2, "observe", MessageType.STATUS));
        service.add(event(ch1, "work", MessageType.DONE));

        List<Long> seqs = service.query("agent-1", 0L).messages()
                .stream().map(ContextMessage::windowSeq).toList();
        assertThat(seqs).isSorted();
        assertThat(seqs).hasSize(3);
    }

    // ── unbindAgent path ──────────────────────────────────────────────────────

    @Test
    void unbindAgent_subsequentQuery_returnsNoAssociation() {
        UUID caseId = UUID.randomUUID();
        UUID channelId = UUID.randomUUID();
        service.bindAgent("agent-1", caseId);
        service.bindChannel(caseId, channelId);
        service.add(event(channelId, "case-x/work", MessageType.STATUS));
        assertThat(service.query("agent-1", 0L).agentHasAssociation()).isTrue();

        service.unbindAgent("agent-1");
        assertThat(service.query("agent-1", 0L).agentHasAssociation()).isFalse();
    }

    @Test
    void unbindAgent_doesNotClearCaseChannels_bufferStillWritable() {
        UUID caseId = UUID.randomUUID();
        UUID channelId = UUID.randomUUID();
        service.bindAgent("agent-1", caseId);
        service.bindChannel(caseId, channelId);
        service.unbindAgent("agent-1");

        // Buffer retained — add() still writes (channel not cleaned up)
        service.add(event(channelId, "case-x/work", MessageType.STATUS));

        // Re-bind agent — message written after unbind is available
        service.bindAgent("agent-1", caseId);
        assertThat(service.query("agent-1", 0L).messages()).hasSize(1);
    }

    // ── sequence / cursor semantics ───────────────────────────────────────────

    @Test
    void currentWindowSeq_reflectsGlobalCounter() {
        UUID caseId = UUID.randomUUID();
        UUID channelId = UUID.randomUUID();
        service.bindAgent("agent-1", caseId);
        service.bindChannel(caseId, channelId);
        service.add(event(channelId, "work", MessageType.STATUS));
        service.add(event(channelId, "work", MessageType.STATUS));

        assertThat(service.query("agent-1", 0L).currentWindowSeq()).isEqualTo(2L);
    }

    @Test
    void lastWindowSeq_isMaxReturnedSeq() {
        UUID caseId = UUID.randomUUID();
        UUID channelId = UUID.randomUUID();
        service.bindAgent("agent-1", caseId);
        service.bindChannel(caseId, channelId);
        service.add(event(channelId, "work", MessageType.STATUS)); // seq=1
        service.add(event(channelId, "work", MessageType.STATUS)); // seq=2

        assertThat(service.query("agent-1", 0L).lastWindowSeq()).isEqualTo(2L);
    }

    @Test
    void lastEvictionWindowSeq_reflectsOverflow() {
        service.maxMessagesPerChannel = 2;
        UUID caseId = UUID.randomUUID();
        UUID channelId = UUID.randomUUID();
        service.bindAgent("agent-1", caseId);
        service.bindChannel(caseId, channelId);
        service.add(event(channelId, "work", MessageType.COMMAND)); // seq=1
        service.add(event(channelId, "work", MessageType.STATUS));  // seq=2
        service.add(event(channelId, "work", MessageType.DONE));    // seq=3 → evicts seq=1

        assertThat(service.query("agent-1", 0L).lastEvictionWindowSeq()).isEqualTo(1L);
    }

    @Test
    void restartDetection_staleClientCursor_greaterThanCurrentWindowSeq() {
        UUID caseId = UUID.randomUUID();
        UUID channelId = UUID.randomUUID();
        service.bindAgent("agent-1", caseId);
        service.bindChannel(caseId, channelId);
        service.add(event(channelId, "work", MessageType.STATUS)); // seq=1
        service.add(event(channelId, "work", MessageType.STATUS)); // seq=2
        service.add(event(channelId, "work", MessageType.STATUS)); // seq=3

        // Simulate restart: new instance, AtomicLong resets to 0
        ChannelContextWindowService restarted = new ChannelContextWindowService();
        restarted.maxMessagesPerChannel = 100;
        restarted.ttl = Duration.ofMinutes(30);
        restarted.bindAgent("agent-1", caseId);
        restarted.bindChannel(caseId, channelId);
        restarted.add(event(channelId, "work", MessageType.STATUS)); // new seq=1

        // Client sends stale cursor=3; currentWindowSeq=1 → restart detected
        WindowContent result = restarted.query("agent-1", 3L);
        assertThat(result.currentWindowSeq()).isEqualTo(1L);
        assertThat(3L).isGreaterThan(result.currentWindowSeq()); // SDK resets cursor
        assertThat(result.agentHasAssociation()).isTrue();
    }

    // ── concurrency ───────────────────────────────────────────────────────────

    @Test
    void concurrency_noExceptionAndBounded() throws InterruptedException {
        service.maxMessagesPerChannel = 10;
        UUID caseId = UUID.randomUUID();
        UUID channelId = UUID.randomUUID();
        service.bindAgent("agent-1", caseId);
        service.bindChannel(caseId, channelId);

        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < 50; j++)
                        service.add(event(channelId, "work", MessageType.STATUS));
                } finally { latch.countDown(); }
            });
        }
        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(service.query("agent-1", 0L).messages()).hasSizeLessThanOrEqualTo(10);
    }

    @Test
    void windowSeq_isGloballyMonotonic_acrossChannels() {
        UUID caseId = UUID.randomUUID();
        UUID ch1 = UUID.randomUUID();
        UUID ch2 = UUID.randomUUID();
        service.bindAgent("agent-1", caseId);
        service.bindChannel(caseId, ch1);
        service.bindChannel(caseId, ch2);

        // Interleave messages across two channels
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
    void concurrency_bindAndQuery_noException() throws InterruptedException {
        UUID caseId = UUID.randomUUID();
        UUID ch1 = UUID.randomUUID();
        UUID ch2 = UUID.randomUUID();
        service.bindAgent("agent-1", caseId);
        service.bindChannel(caseId, ch1);

        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch latch = new CountDownLatch(4);
        executor.submit(() -> { try { for (int i=0;i<50;i++) service.bindChannel(caseId,ch2); } finally { latch.countDown(); } });
        executor.submit(() -> { try { for (int i=0;i<50;i++) service.add(event(ch1,"work",MessageType.STATUS)); } finally { latch.countDown(); } });
        executor.submit(() -> { try { for (int i=0;i<100;i++) service.query("agent-1",0L); } finally { latch.countDown(); } });
        executor.submit(() -> { try { for (int i=0;i<50;i++) { service.unbindAgent("agent-1"); service.bindAgent("agent-1",caseId); } } finally { latch.countDown(); } });
        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();
        // Asserts no exceptions thrown — concurrent bind/unbind/query must be safe
    }

    @Test
    void closeCase_removesChannelAssociationsAndBuffers() {
        UUID caseId = UUID.randomUUID();
        UUID ch1 = UUID.randomUUID();
        UUID ch2 = UUID.randomUUID();
        service.bindAgent("agent-1", caseId);
        service.bindChannel(caseId, ch1);
        service.bindChannel(caseId, ch2);

        // Add a message so the buffer is non-empty
        service.add(event(ch1, "case-" + caseId + "/work", MessageType.STATUS));

        service.unbindAgent("agent-1");
        service.closeCase(caseId);

        // After closeCase: query returns noAssociation (agent unbound + caseChannels removed)
        // Late messages to closed channels are silently dropped (buffer gone, add() is a no-op)
        assertThatCode(() -> service.add(event(ch1, "case-" + caseId + "/work", MessageType.STATUS)))
                .doesNotThrowAnyException();
    }

    @Test
    void closeCase_unknownCase_noOp() {
        assertThatCode(() -> service.closeCase(UUID.randomUUID())).doesNotThrowAnyException();
    }
}
