package io.casehub.openclaw.app;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.casehub.openclaw.context.ChannelContextWindowService;
import io.casehub.openclaw.context.ContextMessage;
import io.casehub.openclaw.context.WindowContent;
import io.casehub.qhorus.api.message.MessageType;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class ChannelContextWindowResourceTest {

    @InjectMock
    ChannelContextWindowService service;

    private WindowContent contentWithOneMessage() {
        ContextMessage msg = new ContextMessage(
                1L, UUID.randomUUID(), "test/work", MessageType.STATUS,
                "finance-agent", "corr-1", "Budget warning", Instant.now());
        return new WindowContent(
                List.of(msg), -1L, 1L, 1L, true,
                Instant.now().minus(Duration.ofMinutes(5)));
    }

    @Test
    void get_knownAgent_returns200WithMessages() {
        when(service.query("test-agent", 0L)).thenReturn(contentWithOneMessage());

        given()
                .when().get("/channel-context/test-agent")
                .then()
                .statusCode(200)
                .body("agentHasAssociation", is(true))
                .body("messages", hasSize(1))
                .body("messages[0].senderId", is("finance-agent"))
                .body("messages[0].messageType", is("STATUS"))
                .body("currentWindowSeq", is(1))
                .body("lastEvictionWindowSeq", is(-1));
    }

    @Test
    void get_unknownAgent_returns200NotAssociated() {
        when(service.query(anyString(), anyLong()))
                .thenReturn(WindowContent.noAssociation());

        given()
                .when().get("/channel-context/ghost-agent")
                .then()
                .statusCode(200)
                .body("agentHasAssociation", is(false))
                .body("messages", hasSize(0));
    }

    @Test
    void get_sinceParamPassedThrough() {
        when(service.query("agent-1", 42L)).thenReturn(WindowContent.noAssociation());

        given()
                .when().get("/channel-context/agent-1?since=42")
                .then()
                .statusCode(200);

        verify(service).query("agent-1", 42L);
    }

    @Test
    void get_sinceDefaultsToZero() {
        when(service.query("agent-1", 0L)).thenReturn(WindowContent.noAssociation());

        given()
                .when().get("/channel-context/agent-1")
                .then()
                .statusCode(200);

        verify(service).query("agent-1", 0L);
    }

    @Test
    void get_queryCallsServiceWithAgentIdAndSince_noPrincipalInteraction() {
        when(service.query("agent-1", 0L)).thenReturn(WindowContent.noAssociation());

        given()
                .when().get("/channel-context/agent-1")
                .then()
                .statusCode(200);

        verify(service).query("agent-1", 0L);
    }
}
