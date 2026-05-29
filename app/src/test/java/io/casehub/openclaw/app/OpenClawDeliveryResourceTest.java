package io.casehub.openclaw.app;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;

@QuarkusTest
class OpenClawDeliveryResourceTest {

    @Test
    void deliver_unknownChannel_returns404() {
        given()
            .contentType(JSON)
            .body("""
                    {"agentId": "finance-agent", "output": "Analysis complete."}
                    """)
        .when()
            .post("/openclaw/delivery/channel/" + UUID.randomUUID())
        .then()
            .statusCode(404);
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
