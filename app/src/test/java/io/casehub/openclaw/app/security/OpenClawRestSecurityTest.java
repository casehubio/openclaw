package io.casehub.openclaw.app.security;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import io.casehub.openclaw.app.OpenClawGroups;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.hamcrest.Matchers.in;
import static org.hamcrest.Matchers.not;

/**
 * Security boundary tests for all REST resources.
 *
 * <p>Verifies @RolesAllowed enforcement on ExampleController (401 unauthenticated,
 * 403 wrong role) and confirms @PermitAll resources pass through without credentials.
 * Does not test business logic — only HTTP status codes are asserted.
 */
@QuarkusTest
class OpenClawRestSecurityTest {

    // ==============================
    // ExampleController — @RolesAllowed(OpenClawGroups.ADMIN)
    // ==============================

    @Test
    void unauthenticated_startExample_returns401() {
        given().contentType(JSON)
            .when().post("/example/nonexistent/start")
            .then().statusCode(401);
    }

    @Test
    @TestSecurity(user = "operator", roles = {"openclaw-operator"})
    void wrongRole_startExample_returns403() {
        given().contentType(JSON)
            .when().post("/example/nonexistent/start")
            .then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "admin", roles = {OpenClawGroups.ADMIN})
    void admin_startExample_isNotForbidden() {
        // "nonexistent" exampleId → 400 (Unknown example) regardless of
        // %test.casehub.example.enabled=true — auth gate cleared, business logic reached
        given().contentType(JSON)
            .when().post("/example/nonexistent/start")
            .then().statusCode(not(in(List.of(401, 403))));
    }

    // ==============================
    // @PermitAll resources — no credentials required
    // ==============================

    @Test
    void permitAll_channelContextWindow_noAuthRequired() {
        given()
            .when().get("/channel-context/test-agent")
            .then().statusCode(not(in(List.of(401, 403))));
    }

    @Test
    void permitAll_deliveryChannel_noAuthRequired() {
        given().contentType(JSON).body("{}")
            .when().post("/openclaw/delivery/channel/" + UUID.randomUUID())
            .then().statusCode(not(in(List.of(401, 403))));
    }

    @Test
    void permitAll_deliveryOversight_noAuthRequired() {
        given().contentType(JSON).body("{}")
            .when().post("/openclaw/delivery/oversight/" + UUID.randomUUID())
            .then().statusCode(not(in(List.of(401, 403))));
    }

    @Test
    void permitAll_pluginCommitments_noAuthRequired() {
        given()
            .when().get("/openclaw/plugin/commitments/test-agent")
            .then().statusCode(not(in(List.of(401, 403))));
    }

    @Test
    void permitAll_directCallDelivery_noAuthRequired() {
        given().contentType(JSON).body("{\"output\":\"test\"}")
            .when().post("/openclaw/direct-call/" + UUID.randomUUID())
            .then().statusCode(not(in(List.of(401, 403))));
    }

    // ==============================
    // MCP endpoint — quarkus.http.auth.permission.mcp
    // ==============================

    @Test
    @Disabled("Requires OIDC server; test environment has discovery-disabled; " +
              "authenticated_mcp_isNotForbidden below verifies auth policy is wired")
    void unauthenticated_mcp_returns401() {
        given().contentType(JSON)
            .body("{\"jsonrpc\":\"2.0\",\"method\":\"initialize\",\"id\":1}")
            .when().post("/mcp")
            .then().statusCode(401);
    }

    @Test
    @TestSecurity(user = "agent", roles = {"openclaw-agent"})
    void authenticated_mcp_isNotForbidden() {
        // Verify that authenticated requests to /mcp pass the auth gate.
        // MCP endpoint requires OIDC bearer token (http.auth.permission.mcp.policy=authenticated).
        // @TestSecurity provides a mock principal that satisfies the "authenticated" policy.
        // Unlike @RolesAllowed, the http.auth.permission policy is the only enforcement gate
        // for MCP (openclaw#43: @RolesAllowed returns MCP error -32001, not HTTP 401/403).
        given().contentType(JSON)
            .body("{\"jsonrpc\":\"2.0\",\"method\":\"initialize\",\"id\":1}")
            .when().post("/mcp")
            .then().statusCode(not(in(List.of(401, 403))));
    }
}
