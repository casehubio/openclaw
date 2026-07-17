package io.casehub.openclaw.app.scenario;

import io.casehub.openclaw.app.OpenClawGroups;
import io.casehub.openclaw.casehub.OversightGateService;
import io.casehub.openclaw.casehub.scenario.ScenarioStateSnapshot;
import io.casehub.openclaw.casehub.scenario.ScenarioStateStore;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
@TestSecurity(user = "admin", roles = {OpenClawGroups.ADMIN})
class ScenarioRestResourceTest {

    @InjectMock
    ScenarioStateStore stateStore;

    @InjectMock
    ScenarioExecutionService executionService;

    @InjectMock
    OversightGateService oversightGateService;

    @Test
    void listScenarios_returnsAll() {
        when(stateStore.listScenarioSummaries()).thenReturn(List.of(
                new ScenarioStateSnapshot("trading-oversight", "idle", List.of(), null, List.of())));

        given()
            .when().get("/api/scenarios")
            .then()
                .statusCode(200)
                .body("[0].scenarioId", equalTo("trading-oversight"))
                .body("[0].status", equalTo("idle"));
    }

    @Test
    void startScenario_returns202() {
        given()
            .when().post("/api/scenarios/trading-oversight/start")
            .then()
                .statusCode(202);

        verify(executionService).start("trading-oversight");
    }

    @Test
    void startScenario_alreadyRunning_returns409() {
        doThrow(new IllegalStateException("Already running"))
                .when(executionService).start("trading-oversight");

        given()
            .when().post("/api/scenarios/trading-oversight/start")
            .then()
                .statusCode(409);
    }

    @Test
    void startScenario_notFound_returns404() {
        doThrow(new IllegalArgumentException("Not found"))
                .when(executionService).start("unknown");

        given()
            .when().post("/api/scenarios/unknown/start")
            .then()
                .statusCode(404);
    }

    @Test
    void getState_returnsSnapshot() {
        when(stateStore.currentState("trading-oversight")).thenReturn(
                new ScenarioStateSnapshot("trading-oversight", "running", List.of(), null, List.of()));

        given()
            .when().get("/api/scenarios/trading-oversight/state")
            .then()
                .statusCode(200)
                .body("scenarioId", equalTo("trading-oversight"))
                .body("status", equalTo("running"));
    }

    @Test
    void completeWorkitem_approve_callsFulfill() {
        UUID gateId = UUID.randomUUID();
        given()
                .contentType("application/json")
                .body("{\"outcome\":\"approve\"}")
                .when().put("/api/scenarios/trading-oversight/workitems/" + gateId + "/complete")
                .then()
                .statusCode(200);

        verify(oversightGateService).fulfill(gateId, "Approved");
    }

    @Test
    void completeWorkitem_reject_callsFulfill() {
        UUID gateId = UUID.randomUUID();
        given()
                .contentType("application/json")
                .body("{\"outcome\":\"reject\"}")
                .when().put("/api/scenarios/trading-oversight/workitems/" + gateId + "/complete")
                .then()
                .statusCode(200);

        verify(oversightGateService).fulfill(gateId, "Rejected");
    }


}
