package io.casehub.openclaw.app;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;

@QuarkusTest
class OpenClawOversightDeliveryResourceTest {

    @Test
    void deliver_validGateId_returns200() {
        given()
            .contentType(JSON)
            .body("""
                    {"agentId": "finance-agent", "output": "approved"}
                    """)
        .when()
            .post("/openclaw/delivery/oversight/" + UUID.randomUUID())
        .then()
            .statusCode(200);
    }

    @Test
    void deliver_invalidGateId_returns400() {
        given()
            .contentType(JSON)
            .body("""
                    {"agentId": "finance-agent", "output": "approved"}
                    """)
        .when()
            .post("/openclaw/delivery/oversight/not-a-uuid")
        .then()
            .statusCode(400);
    }

    @Test
    void deliver_unknownGateId_returns200() {
        // OversightGateService.fulfill() fails-open when commitment not found
        given()
            .contentType(JSON)
            .body("""
                    {"agentId": "finance-agent", "output": "rejected"}
                    """)
        .when()
            .post("/openclaw/delivery/oversight/" + UUID.randomUUID())
        .then()
            .statusCode(200);
    }
}
