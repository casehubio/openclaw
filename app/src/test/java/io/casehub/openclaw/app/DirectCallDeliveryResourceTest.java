package io.casehub.openclaw.app;

import io.casehub.openclaw.casehub.DirectCallBridge;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class DirectCallDeliveryResourceTest {

    @Test
    void deliver_completesTheBridge() {
        DirectCallBridge bridge = new DirectCallBridge();
        DirectCallDeliveryResource resource = new DirectCallDeliveryResource(bridge);
        CompletableFuture<String> future = bridge.submit("corr-1", Duration.ofSeconds(30));

        Response response = resource.deliver("corr-1",
                new DirectCallDeliveryPayload("result text"));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(future.isDone()).isTrue();
        assertThat(future.getNow(null)).isEqualTo("result text");
    }

    @Test
    void deliver_unknownCorrelationId_returns200() {
        DirectCallBridge bridge = new DirectCallBridge();
        DirectCallDeliveryResource resource = new DirectCallDeliveryResource(bridge);

        Response response = resource.deliver("unknown",
                new DirectCallDeliveryPayload("text"));

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void deliver_nullPayload_returns200WithEmptyOutput() {
        DirectCallBridge bridge = new DirectCallBridge();
        DirectCallDeliveryResource resource = new DirectCallDeliveryResource(bridge);
        CompletableFuture<String> future = bridge.submit("corr-1", Duration.ofSeconds(30));

        Response response = resource.deliver("corr-1", null);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(future.getNow(null)).isEqualTo("");
    }

    @Test
    void deliver_nullOutput_returns200WithEmptyString() {
        DirectCallBridge bridge = new DirectCallBridge();
        DirectCallDeliveryResource resource = new DirectCallDeliveryResource(bridge);
        CompletableFuture<String> future = bridge.submit("corr-1", Duration.ofSeconds(30));

        Response response = resource.deliver("corr-1",
                new DirectCallDeliveryPayload(null));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(future.getNow(null)).isEqualTo("");
    }

    @Test
    void deliver_exceptionInBridge_stillReturns200() {
        DirectCallBridge bridge = new DirectCallBridge();
        DirectCallDeliveryResource resource = new DirectCallDeliveryResource(bridge);
        CompletableFuture<String> future = bridge.submit("corr-1", Duration.ofSeconds(30));
        bridge.cancel("corr-1");

        Response response = resource.deliver("corr-1",
                new DirectCallDeliveryPayload("late response"));

        assertThat(response.getStatus()).isEqualTo(200);
    }
}
