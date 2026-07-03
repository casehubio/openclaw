package io.casehub.openclaw.casehub.scenario;

import java.util.List;

public record ScenarioStateSnapshot(
    String scenarioId,
    String status,
    List<AgentState> agents,
    GateState pendingGate,
    List<CaseExecutionEvent.ChannelMessageEvent> recentMessages
) {}
