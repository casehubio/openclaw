package io.casehub.openclaw.casehub;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class DirectCallBridgeTest {

    @Test
    void submit_createsAndReturnsFuture() {
        DirectCallBridge bridge = new DirectCallBridge();
        CompletableFuture<String> future = bridge.submit("corr-1");
        assertThat(future).isNotNull();
        assertThat(future.isDone()).isFalse();
    }

    @Test
    void complete_resolvesFuture() throws Exception {
        DirectCallBridge bridge = new DirectCallBridge();
        CompletableFuture<String> future = bridge.submit("corr-1");
        bridge.complete("corr-1", "{\"result\":\"ok\"}");
        assertThat(future.isDone()).isTrue();
        assertThat(future.get()).isEqualTo("{\"result\":\"ok\"}");
    }

    @Test
    void cancel_cancelsFuture() {
        DirectCallBridge bridge = new DirectCallBridge();
        CompletableFuture<String> future = bridge.submit("corr-1");
        bridge.cancel("corr-1");
        assertThat(future.isCancelled()).isTrue();
    }

    @Test
    void complete_afterCancel_isNoOp() {
        DirectCallBridge bridge = new DirectCallBridge();
        CompletableFuture<String> future = bridge.submit("corr-1");
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
        CompletableFuture<String> first = bridge.submit("corr-1");
        CompletableFuture<String> second = bridge.submit("corr-1");
        assertThat(second).isSameAs(first);
    }
}
