package io.casehub.openclaw.app.example;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.casehub.qhorus.api.message.CommitmentState;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class ExampleControllerTest {

    @InjectMock
    ExampleSetup exampleSetup;

    @InjectMock
    ExamplePoller examplePoller;

    @Test
    void unknownExampleId_returns400() {
        given()
            .contentType(JSON)
        .when()
            .post("/example/does-not-exist/start")
        .then()
            .statusCode(400);

        verify(exampleSetup, never()).setupAndDispatch(any(), any(), any(), any(), any(), any());
    }

    @Test
    void multiAgentDevTeam_runsThroughPlannerCoderReviewer() {
        doNothing().when(exampleSetup).setupAndDispatch(any(), any(), any(), any(), any(), any());
        // All agents complete immediately with FULFILLED
        when(examplePoller.checkState(any())).thenReturn(CommitmentState.FULFILLED);

        given()
            .contentType(JSON)
        .when()
            .post("/example/multi-agent-dev-team/start")
        .then()
            .statusCode(200);

        // Planner, Coder, Reviewer — 3 setup calls
        ArgumentCaptor<String> agentCaptor = ArgumentCaptor.forClass(String.class);
        verify(exampleSetup, org.mockito.Mockito.times(3))
                .setupAndDispatch(any(), any(), agentCaptor.capture(), any(), any(), any());
        assertThat(agentCaptor.getAllValues()).containsExactly("planner", "coder", "reviewer");
    }

    @Test
    void tradingOversight_runsSignalRiskExecution() {
        doNothing().when(exampleSetup).setupAndDispatch(any(), any(), any(), any(), any(), any());
        when(examplePoller.checkState(any())).thenReturn(CommitmentState.FULFILLED);

        given()
            .contentType(JSON)
        .when()
            .post("/example/trading-oversight/start")
        .then()
            .statusCode(200);

        ArgumentCaptor<String> agentCaptor = ArgumentCaptor.forClass(String.class);
        verify(exampleSetup, org.mockito.Mockito.times(3))
                .setupAndDispatch(any(), any(), agentCaptor.capture(), any(), any(), any());
        assertThat(agentCaptor.getAllValues()).containsExactly("signal", "risk", "execution");
    }

    @Test
    void incidentResponse_runsInvestigatorResolver() {
        doNothing().when(exampleSetup).setupAndDispatch(any(), any(), any(), any(), any(), any());
        when(examplePoller.checkState(any())).thenReturn(CommitmentState.FULFILLED);

        given()
            .contentType(JSON)
        .when()
            .post("/example/incident-response/start")
        .then()
            .statusCode(200);

        ArgumentCaptor<String> agentCaptor = ArgumentCaptor.forClass(String.class);
        verify(exampleSetup, org.mockito.Mockito.times(2))
                .setupAndDispatch(any(), any(), agentCaptor.capture(), any(), any(), any());
        assertThat(agentCaptor.getAllValues()).containsExactly("investigator", "resolver");
    }

    @Test
    void declined_stops_doesNotRunNextAgent() {
        doNothing().when(exampleSetup).setupAndDispatch(any(), any(), any(), any(), any(), any());
        // Signal completes, Risk declines
        when(examplePoller.checkState(any()))
                .thenReturn(CommitmentState.FULFILLED)   // signal
                .thenReturn(CommitmentState.DECLINED);   // risk

        given()
            .contentType(JSON)
        .when()
            .post("/example/trading-oversight/start")
        .then()
            .statusCode(200);

        // Only signal + risk dispatched, not execution
        verify(exampleSetup, org.mockito.Mockito.times(2))
                .setupAndDispatch(any(), any(), any(), any(), any(), any());
    }

    @Test
    void delegated_stops_doesNotRunNextAgent() {
        doNothing().when(exampleSetup).setupAndDispatch(any(), any(), any(), any(), any(), any());
        // Investigator escalates → DELEGATED
        when(examplePoller.checkState(any())).thenReturn(CommitmentState.DELEGATED);

        given()
            .contentType(JSON)
        .when()
            .post("/example/incident-response/start")
        .then()
            .statusCode(200);

        // Only investigator dispatched, not resolver
        verify(exampleSetup, org.mockito.Mockito.times(1))
                .setupAndDispatch(any(), any(), eq("investigator"), any(), any(), any());
        verify(exampleSetup, never())
                .setupAndDispatch(any(), any(), eq("resolver"), any(), any(), any());
    }
}
