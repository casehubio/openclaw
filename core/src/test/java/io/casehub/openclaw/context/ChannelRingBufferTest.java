package io.casehub.openclaw.context;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.message.MessageType;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelRingBufferTest {

    static final int MAX_SIZE = 3;
    static final Duration TTL = Duration.ofMinutes(30);

    ChannelRingBuffer buffer;

    @BeforeEach
    void setup() {
        buffer = new ChannelRingBuffer(MAX_SIZE, TTL);
    }

    private ContextMessage msg(long seq) {
        return new ContextMessage(seq, UUID.randomUUID(), "test/channel",
                MessageType.STATUS, "sender-1", "corr-1", "content", Instant.now());
    }

    private ContextMessage expiredMsg(long seq) {
        Instant past = Instant.now().minus(Duration.ofHours(1));
        return new ContextMessage(seq, UUID.randomUUID(), "test/channel",
                MessageType.STATUS, "sender-1", "corr-1", "old content", past);
    }

    @Test
    void initialState_lastActivityIsEpoch() {
        assertThat(buffer.lastActivity()).isEqualTo(Instant.EPOCH);
    }

    @Test
    void initialState_lastEvictionWindowSeqIsMinusOne() {
        assertThat(buffer.lastEvictionWindowSeq()).isEqualTo(-1L);
    }

    @Test
    void query_emptyBuffer_returnsEmpty() {
        assertThat(buffer.query(0, Instant.now())).isEmpty();
    }

    @Test
    void add_withinCapacity_allReturned() {
        buffer.add(msg(1));
        buffer.add(msg(2));
        assertThat(buffer.query(0, Instant.now())).hasSize(2);
    }

    @Test
    void add_overflow_oldestEvicted_lastEvictionSeqUpdated() {
        buffer.add(msg(1));
        buffer.add(msg(2));
        buffer.add(msg(3));
        buffer.add(msg(4)); // overflows: seq=1 is evicted

        assertThat(buffer.lastEvictionWindowSeq()).isEqualTo(1L);
        assertThat(buffer.query(0, Instant.now()))
                .extracting(ContextMessage::windowSeq)
                .containsExactly(2L, 3L, 4L);
    }

    @Test
    void add_multipleOverflows_lastEvictionSeqIsNewest() {
        buffer.add(msg(1));
        buffer.add(msg(2));
        buffer.add(msg(3));
        buffer.add(msg(4)); // evicts seq=1, lastEviction=1
        buffer.add(msg(5)); // evicts seq=2, lastEviction=2

        assertThat(buffer.lastEvictionWindowSeq()).isEqualTo(2L);
    }

    @Test
    void query_withSince_returnsOnlyNewer() {
        buffer.add(msg(1));
        buffer.add(msg(2));
        buffer.add(msg(3));

        assertThat(buffer.query(2, Instant.now()))
                .extracting(ContextMessage::windowSeq)
                .containsExactly(3L);
    }

    @Test
    void query_ttlFilter_expiredMessagesExcluded() {
        buffer.add(expiredMsg(1));
        buffer.add(msg(2));

        assertThat(buffer.query(0, Instant.now()))
                .extracting(ContextMessage::windowSeq)
                .containsExactly(2L);
    }

    @Test
    void evictExpired_removesStaleEntries() {
        buffer.add(expiredMsg(1));
        buffer.add(msg(2));

        buffer.evictExpired(Instant.now());

        assertThat(buffer.query(0, Instant.now())).hasSize(1)
                .extracting(ContextMessage::windowSeq).containsExactly(2L);
    }

    @Test
    void lastActivity_updatedOnAdd() {
        Instant before = Instant.now().minusSeconds(1);
        buffer.add(msg(1));
        assertThat(buffer.lastActivity()).isAfter(before);
    }

    @Test
    void lastActivity_notUpdatedByEvictExpired() {
        buffer.add(msg(1));
        Instant afterAdd = buffer.lastActivity();
        buffer.evictExpired(Instant.now());
        assertThat(buffer.lastActivity()).isEqualTo(afterAdd);
    }
}
