package io.casehub.openclaw.app.security;

import io.casehub.openclaw.app.OpenClawGroups;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

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
    // Scenario REST — read endpoints @PermitAll, mutation @RolesAllowed(ADMIN)
    // ==============================

    @Test
    void unauthenticated_listScenarios_passes() {
        given()
            .when().get("/api/scenarios")
            .then().statusCode(not(in(List.of(401, 403))));
    }

    @Test
    void unauthenticated_scenarioState_passes() {
        given()
            .when().get("/api/scenarios/nonexistent/state")
            .then().statusCode(not(in(List.of(401, 403))));
    }

    @Test
    void unauthenticated_startScenario_returns401() {
        given().contentType(JSON)
            .when().post("/api/scenarios/nonexistent/start")
            .then().statusCode(401);
    }

    @Test
    @TestSecurity(user = "operator", roles = {"openclaw-operator"})
    void wrongRole_startScenario_returns403() {
        given().contentType(JSON)
            .when().post("/api/scenarios/nonexistent/start")
            .then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "admin", roles = {OpenClawGroups.ADMIN})
    void admin_startScenario_isNotForbidden() {
        given().contentType(JSON)
            .when().post("/api/scenarios/nonexistent/start")
            .then().statusCode(not(in(List.of(401, 403))));
    }

    @Test
    void unauthenticated_completeWorkitem_returns401() {
        given()
                .contentType("application/json")
                .body("{\"outcome\":\"approve\"}")
                .when().put("/api/scenarios/trading-oversight/workitems/00000000-0000-0000-0000-000000000001/complete")
                .then()
                .statusCode(401);
    }

    @Test
    @TestSecurity(user = "admin", roles = {OpenClawGroups.ADMIN})
    void admin_completeWorkitem_isNotForbidden() {
        given()
                .contentType("application/json")
                .body("{\"outcome\":\"approve\"}")
                .when().put("/api/scenarios/trading-oversight/workitems/00000000-0000-0000-0000-000000000001/complete")
                .then()
                .statusCode(not(in(List.of(401, 403))));
    }


    // ==============================
    // Channel-context — @RolesAllowed(OpenClawGroups.PLUGIN) + plugin-token mechanism
    // ==============================

    @Test
    void unauthenticated_channelContext_returns401() {
        given()
            .when().get("/channel-context/test-agent")
            .then().statusCode(401);
    }

    @Test
    @TestSecurity(user = "plugin", roles = {OpenClawGroups.PLUGIN})
    void pluginRole_channelContext_passes() {
        given()
            .when().get("/channel-context/test-agent")
            .then().statusCode(not(in(List.of(401, 403))));
    }

    @Test
    void validBearerToken_channelContext_passes() {
        given()
            .header("Authorization", "Bearer test-plugin-token")
            .when().get("/channel-context/test-agent")
            .then().statusCode(not(in(List.of(401, 403))));
    }

    // ==============================
    // Delivery endpoints — token-validated via query parameter
    // ==============================

    @Test
    void deliveryChannel_noToken_returns403() {
        given().contentType(JSON).body("{}")
            .when().post("/openclaw/delivery/channel/" + UUID.randomUUID())
            .then().statusCode(403);
    }

    @Test
    void deliveryChannel_validToken_passes() {
        given().contentType(JSON).body("{}")
            .queryParam("token", "test-delivery-token")
            .when().post("/openclaw/delivery/channel/" + UUID.randomUUID())
            .then().statusCode(not(in(List.of(401, 403))));
    }

    @Test
    void deliveryChannel_wrongToken_returns403() {
        given().contentType(JSON).body("{}")
            .queryParam("token", "wrong-token")
            .when().post("/openclaw/delivery/channel/" + UUID.randomUUID())
            .then().statusCode(403);
    }

    @Test
    void deliveryOversight_noToken_returns403() {
        given().contentType(JSON).body("{}")
            .when().post("/openclaw/delivery/oversight/" + UUID.randomUUID())
            .then().statusCode(403);
    }

    @Test
    void deliveryOversight_validToken_passes() {
        given().contentType(JSON).body("{}")
            .queryParam("token", "test-delivery-token")
            .when().post("/openclaw/delivery/oversight/" + UUID.randomUUID())
            .then().statusCode(not(in(List.of(401, 403))));
    }

    // ==============================
    // Plugin endpoints — @RolesAllowed(OpenClawGroups.PLUGIN) + plugin-token mechanism
    // ==============================

    @Test
    void unauthenticated_plugin_returns401() {
        given()
            .when().get("/openclaw/plugin/commitments/test-agent")
            .then().statusCode(401);
    }

    @Test
    @TestSecurity(user = "plugin", roles = {OpenClawGroups.PLUGIN})
    void authenticated_plugin_isNotForbidden() {
        given()
            .when().get("/openclaw/plugin/commitments/test-agent")
            .then().statusCode(not(in(List.of(401, 403))));
    }

    @Test
    void validBearerToken_plugin_passesAuth() {
        given()
            .header("Authorization", "Bearer test-plugin-token")
            .when().get("/openclaw/plugin/commitments/test-agent")
            .then().statusCode(not(in(List.of(401, 403))));
    }

    @Test
    @Disabled("Bearer header reaches OIDC mechanism which tries JWT validation → " +
              "challenge connects to localhost:8180 (no server) → 500; " +
              "in production OIDC returns 401 for invalid JWT format")
    void invalidBearerToken_plugin_returns401() {
        given()
            .header("Authorization", "Bearer wrong-token")
            .when().get("/openclaw/plugin/commitments/test-agent")
            .then().statusCode(401);
    }

    @Test
    @Disabled("Bearer header reaches OIDC mechanism → challenge → 500; " +
              "mechanism isolation verified by path guard (returns null for non-plugin paths)")
    void pluginToken_doesNotAuthenticateMcpEndpoint() {
        given()
            .header("Authorization", "Bearer test-plugin-token")
            .contentType(JSON)
            .body("{\"jsonrpc\":\"2.0\",\"method\":\"initialize\",\"id\":1}")
            .when().post("/mcp")
            .then().statusCode(401);
    }

    @Test
    void directCallDelivery_noToken_returns403() {
        given().contentType(JSON).body("{\"output\":\"test\"}")
            .when().post("/openclaw/direct-call/" + UUID.randomUUID())
            .then().statusCode(403);
    }

    @Test
    void directCallDelivery_validToken_passes() {
        given().contentType(JSON).body("{\"output\":\"test\"}")
            .queryParam("token", "test-delivery-token")
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
