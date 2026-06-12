package io.casehub.openclaw.app;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.casehub.openclaw.casehub.OversightGateService;
import io.casehub.qhorus.api.qualifier.CrossTenant;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.store.CrossTenantChannelStore;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class OpenClawDeliveryResourceTest {

    @InjectMock
    @CrossTenant
    CrossTenantChannelStore crossTenantChannelStore;

    @InjectMock
    OversightGateService oversightGateService;

    private Channel channelWithTenancy(UUID id, String tenancyId) {
        Channel ch = new Channel();
        ch.id = id;
        ch.tenancyId = tenancyId;
        return ch;
    }

    @Test
    void deliver_knownChannel_returns200AndPassesTenancyId() {
        UUID channelId = UUID.randomUUID();
        String tenancyId = "tenant-abc";
        when(crossTenantChannelStore.findById(channelId))
                .thenReturn(Optional.of(channelWithTenancy(channelId, tenancyId)));

        given()
            .contentType(JSON)
            .body("""
                    {"agentId": "finance-agent", "output": "Analysis complete."}
                    """)
        .when()
            .post("/openclaw/delivery/channel/" + channelId)
        .then()
            .statusCode(200);

        verify(oversightGateService).evaluate(eq(channelId), eq(tenancyId), eq("finance-agent"),
                eq("Analysis complete."));
    }

    @Test
    void deliver_unknownChannel_returns200WithNullTenancyId() {
        UUID channelId = UUID.randomUUID();
        when(crossTenantChannelStore.findById(channelId)).thenReturn(Optional.empty());

        given()
            .contentType(JSON)
            .body("""
                    {"agentId": "finance-agent", "output": "Analysis complete."}
                    """)
        .when()
            .post("/openclaw/delivery/channel/" + channelId)
        .then()
            .statusCode(200);

        verify(oversightGateService).evaluate(eq(channelId), isNull(), eq("finance-agent"),
                eq("Analysis complete."));
    }

    @Test
    void deliver_invalidUuid_returns400() {
        given()
            .contentType(JSON)
            .body("""
                    {"agentId": "finance-agent", "output": "Analysis complete."}
                    """)
        .when()
            .post("/openclaw/delivery/channel/not-a-uuid")
        .then()
            .statusCode(400);
    }
}
