package io.casehub.openclaw.casehub;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DirectCallBridgeTest {

    @Test
    void submit_createsAndReturnsFuture() {
        DirectCallBridge bridge = new DirectCallBridge();
        CompletableFuture<String> future = bridge.submit("corr-1", Duration.ofSeconds(30));
        assertThat(future).isNotNull();
        assertThat(future.isDone()).isFalse();
    }

    @Test
    void complete_resolvesFuture() throws Exception {
        DirectCallBridge bridge = new DirectCallBridge();
        CompletableFuture<String> future = bridge.submit("corr-1", Duration.ofSeconds(30));
        bridge.complete("corr-1", "{\"result\":\"ok\"}");
        assertThat(future.isDone()).isTrue();
        assertThat(future.get()).isEqualTo("{\"result\":\"ok\"}");
    }

    @Test
    void cancel_cancelsFuture() {
        DirectCallBridge bridge = new DirectCallBridge();
        CompletableFuture<String> future = bridge.submit("corr-1", Duration.ofSeconds(30));
        bridge.cancel("corr-1");
        assertThat(future.isCancelled()).isTrue();
    }

    @Test
    void complete_afterCancel_isNoOp() {
        DirectCallBridge bridge = new DirectCallBridge();
        CompletableFuture<String> future = bridge.submit("corr-1", Duration.ofSeconds(30));
        bridge.cancel("corr-1");
        bridge.complete("corr-1", "late response");
        assertThat(future.isCancelled()).isTrue();
    }

    @Test
    void complete_unknownCorrelationId_isNoOp() {
        DirectCallBridge bridge = new DirectCallBridge();
        bridge.complete("unknown", "response");
    }

    @Test
    void cancel_unknownCorrelationId_isNoOp() {
        DirectCallBridge bridge = new DirectCallBridge();
        bridge.cancel("unknown");
    }

    @Test
    void submit_duplicateCorrelationId_returnsExistingFuture() {
        DirectCallBridge bridge = new DirectCallBridge();
        CompletableFuture<String> first = bridge.submit("corr-1", Duration.ofSeconds(30));
        CompletableFuture<String> second = bridge.submit("corr-1", Duration.ofSeconds(30));
        assertThat(second).isSameAs(first);
    }

    @Test
    void submit_withTimeout_selfEvictsOnTimeout() throws Exception {
        DirectCallBridge bridge = new DirectCallBridge();
        CompletableFuture<String> future = bridge.submit("corr-1", Duration.ofMillis(50));
        Thread.sleep(200);
        assertThat(future.isCompletedExceptionally()).isTrue();
        assertThatThrownBy(future::get)
                .hasCauseInstanceOf(TimeoutException.class);
        // Verify map cleanup: a new submit should NOT return the timed-out future
        CompletableFuture<String> fresh = bridge.submit("corr-1", Duration.ofSeconds(10));
        assertThat(fresh).isNotSameAs(future);
        assertThat(fresh.isDone()).isFalse();
    }
}
