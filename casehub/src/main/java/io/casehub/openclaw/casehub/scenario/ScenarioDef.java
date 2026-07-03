package io.casehub.openclaw.casehub.scenario;

import java.util.List;
import java.util.UUID;

public record ScenarioDef(String id, String name, String description,
                          List<AgentDef> agents, String gateAgentId, UUID caseId) {}
