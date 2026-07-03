package io.casehub.openclaw.casehub.scenario;

public record GateState(String gateId, String agentId, String action,
                        String classification, String priorAgents) {}
