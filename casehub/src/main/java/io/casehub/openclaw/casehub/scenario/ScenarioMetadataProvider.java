package io.casehub.openclaw.casehub.scenario;

import java.util.*;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class ScenarioMetadataProvider {

    // Fixed case IDs — same as ExampleController (enables ChannelContextWindow accumulation)
    static final UUID CASE_ID_TRADING   = UUID.fromString("00000002-0000-0000-0000-000000000002");
    static final UUID CASE_ID_DEV_TEAM  = UUID.fromString("00000001-0000-0000-0000-000000000001");
    static final UUID CASE_ID_INCIDENT  = UUID.fromString("00000003-0000-0000-0000-000000000003");

    private static final Map<String, ScenarioDef> SCENARIOS = Map.of(
            "trading-oversight", new ScenarioDef(
                    "trading-oversight", "Trading Oversight",
                    "AI agents analyse market signals, assess risk, and execute trades — with human oversight before execution.",
                    List.of(
                            new AgentDef("signal", "Signal Analyst", "Analyses market data for trading signals", 1),
                            new AgentDef("risk", "Risk Assessor", "Evaluates trade risk exposure", 2),
                            new AgentDef("execution", "Trade Executor", "Executes approved trades", 3)),
                    "execution", CASE_ID_TRADING),
            "multi-agent-dev-team", new ScenarioDef(
                    "multi-agent-dev-team", "Multi-Agent Dev Team",
                    "A dev team of AI agents plans, codes, and reviews a fix — with human approval before merge.",
                    List.of(
                            new AgentDef("planner", "Planner", "Decomposes issues into development tasks", 1),
                            new AgentDef("coder", "Coder", "Implements the fix and runs CI", 2),
                            new AgentDef("reviewer", "Reviewer", "Reviews the pull request diff", 3)),
                    "reviewer", CASE_ID_DEV_TEAM),
            "incident-response", new ScenarioDef(
                    "incident-response", "Incident Response",
                    "AI agents investigate and resolve a P1 incident — with human oversight before applying the fix.",
                    List.of(
                            new AgentDef("investigator", "Investigator", "Investigates the root cause of the incident", 1),
                            new AgentDef("resolver", "Resolver", "Applies the remediation", 2)),
                    "resolver", CASE_ID_INCIDENT));

    private static final Map<UUID, ScenarioDef> BY_CASE_ID;

    static {
        var map = new HashMap<UUID, ScenarioDef>();
        SCENARIOS.values().forEach(def -> map.put(def.caseId(), def));
        BY_CASE_ID = Collections.unmodifiableMap(map);
    }

    public Map<String, ScenarioDef> allScenarios() {
        return SCENARIOS;
    }

    public Optional<ScenarioDef> scenarioForCaseId(UUID caseId) {
        return Optional.ofNullable(BY_CASE_ID.get(caseId));
    }

    public Set<UUID> demoCaseIds() {
        return BY_CASE_ID.keySet();
    }
}
