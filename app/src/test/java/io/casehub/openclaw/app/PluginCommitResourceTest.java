package io.casehub.openclaw.app;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.message.CommitmentState;
import io.casehub.qhorus.runtime.message.Commitment;
import io.casehub.qhorus.runtime.message.CommitmentService;
import io.casehub.qhorus.runtime.store.CommitmentStore;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Tests for /openclaw/plugin/* endpoints consumed by the TypeScript plugin
 * for auto-commit lifecycle management.
 */
@QuarkusTest
@TestSecurity(user = "plugin", roles = {OpenClawGroups.PLUGIN})
class PluginCommitResourceTest {

    @InjectMock
    CommitmentService commitmentService;

    @InjectMock
    CommitmentStore commitmentStore;

    // ---- POST /openclaw/plugin/commit ----

    @Test
    void commit_validBody_returns200WithCommitmentId() {
        Commitment c = commitment(UUID.randomUUID().toString(), UUID.randomUUID(),
                "finance-agent", CommitmentState.OPEN, Instant.now().plusSeconds(3600));
        when(commitmentService.open(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(c);

        given()
            .contentType(JSON)
            .body("""
                    {"agentId": "finance-agent", "task": "Run quarterly report"}
                    """)
        .when()
            .post("/openclaw/plugin/commit")
        .then()
            .statusCode(200)
            .body("commitmentId", is(c.correlationId));
    }

    @Test
    void commit_missingAgentId_returns400() {
        given()
            .contentType(JSON)
            .body("""
                    {"task": "Run report"}
                    """)
        .when()
            .post("/openclaw/plugin/commit")
        .then()
            .statusCode(400);
    }

    // ---- POST /openclaw/plugin/done ----

    @Test
    void done_validCommitmentId_returns200() {
        Commitment c = commitment(UUID.randomUUID().toString(), UUID.randomUUID(),
                "finance-agent", CommitmentState.FULFILLED, null);
        when(commitmentService.fulfill(any())).thenReturn(java.util.Optional.of(c));

        given()
            .contentType(JSON)
            .body("""
                    {"agentId": "finance-agent", "commitmentId": "c-abc123"}
                    """)
        .when()
            .post("/openclaw/plugin/done")
        .then()
            .statusCode(200)
            .body("closed", is(true));
    }

    @Test
    void done_missingCommitmentId_returns400() {
        given()
            .contentType(JSON)
            .body("""
                    {"agentId": "finance-agent"}
                    """)
        .when()
            .post("/openclaw/plugin/done")
        .then()
            .statusCode(400);
    }

    // ---- GET /openclaw/plugin/commitments/{agentId} ----

    @Test
    void commitments_returnsOpenCommitmentsForAgent() {
        String agentId = "finance-agent";
        String correlationId = UUID.randomUUID().toString();
        Commitment open = commitment(correlationId, UUID.randomUUID(), agentId,
                CommitmentState.OPEN, Instant.parse("2026-06-04T17:00:00Z"));
        when(commitmentStore.findAllOpen()).thenReturn(List.of(open));

        given()
        .when()
            .get("/openclaw/plugin/commitments/" + agentId)
        .then()
            .statusCode(200)
            .body("count", is(1))
            .body("open[0].commitmentId", is(correlationId))
            .body("open[0].state", is("OPEN"));
    }

    @Test
    void commitments_noOpenCommitments_returnsEmptyList() {
        when(commitmentStore.findAllOpen()).thenReturn(List.of());

        given()
        .when()
            .get("/openclaw/plugin/commitments/unknown-agent")
        .then()
            .statusCode(200)
            .body("count", is(0));
    }

    // ---- helpers ----

    private static Commitment commitment(String correlationId, UUID channelId,
                                          String obligor, CommitmentState state, Instant expiresAt) {
        Commitment c = new Commitment();
        c.id = UUID.randomUUID();
        c.correlationId = correlationId;
        c.channelId = channelId;
        c.obligor = obligor;
        c.state = state;
        c.expiresAt = expiresAt;
        return c;
    }
}
