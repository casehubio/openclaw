package io.casehub.openclaw.casehub.scenario;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = CaseExecutionEvent.ScenarioStartedEvent.class, name = "SCENARIO_STARTED"),
    @JsonSubTypes.Type(value = CaseExecutionEvent.ScenarioCompletedEvent.class, name = "SCENARIO_COMPLETED"),
    @JsonSubTypes.Type(value = CaseExecutionEvent.ScenarioFailedEvent.class, name = "SCENARIO_FAILED"),
    @JsonSubTypes.Type(value = CaseExecutionEvent.AgentStartedEvent.class, name = "AGENT_STARTED"),
    @JsonSubTypes.Type(value = CaseExecutionEvent.AgentCompletedEvent.class, name = "AGENT_COMPLETED"),
    @JsonSubTypes.Type(value = CaseExecutionEvent.CommitmentUpdatedEvent.class, name = "COMMITMENT_UPDATED"),
    @JsonSubTypes.Type(value = CaseExecutionEvent.ChannelMessageEvent.class, name = "CHANNEL_MESSAGE"),
    @JsonSubTypes.Type(value = CaseExecutionEvent.GatePendingEvent.class, name = "GATE_PENDING"),
    @JsonSubTypes.Type(value = CaseExecutionEvent.GateResolvedEvent.class, name = "GATE_RESOLVED"),
})
public sealed interface CaseExecutionEvent {
    String scenarioId();
    Instant occurredAt();

    record ScenarioStartedEvent(String scenarioId, Instant occurredAt) implements CaseExecutionEvent {}
    record ScenarioCompletedEvent(String scenarioId, Instant occurredAt) implements CaseExecutionEvent {}
    record ScenarioFailedEvent(String scenarioId, Instant occurredAt, String error) implements CaseExecutionEvent {}

    record AgentStartedEvent(String scenarioId, Instant occurredAt,
        String agentId, String role) implements CaseExecutionEvent {}
    record AgentCompletedEvent(String scenarioId, Instant occurredAt,
        String agentId, String role, String outcome, long durationMs) implements CaseExecutionEvent {}

    record CommitmentUpdatedEvent(String scenarioId, Instant occurredAt,
        String agentId, String commitmentId, String state, String outcome) implements CaseExecutionEvent {}

    record ChannelMessageEvent(String scenarioId, Instant occurredAt,
        String agentId, String role, String content) implements CaseExecutionEvent {}

    record GatePendingEvent(String scenarioId, Instant occurredAt,
        String gateId, String agentId, String action, String classification,
        String priorAgents) implements CaseExecutionEvent {}

    record GateResolvedEvent(String scenarioId, Instant occurredAt,
        String gateId, String decision) implements CaseExecutionEvent {}
}
