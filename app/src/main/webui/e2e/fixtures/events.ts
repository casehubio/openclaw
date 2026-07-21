const DEFAULT_SCENARIO = 'trading-oversight';
const now = () => new Date().toISOString();

export const scenarioStarted = (scenarioId = DEFAULT_SCENARIO) =>
  ({ type: 'SCENARIO_STARTED' as const, scenarioId, occurredAt: now() });

export const scenarioCompleted = (scenarioId = DEFAULT_SCENARIO) =>
  ({ type: 'SCENARIO_COMPLETED' as const, scenarioId, occurredAt: now() });

export const scenarioFailed = (error: string, scenarioId = DEFAULT_SCENARIO) =>
  ({ type: 'SCENARIO_FAILED' as const, scenarioId, occurredAt: now(), error });

export const agentStarted = (agentId: string, role: string, scenarioId = DEFAULT_SCENARIO) =>
  ({ type: 'AGENT_STARTED' as const, scenarioId, occurredAt: now(), agentId, role });

export const agentCompleted = (agentId: string, role: string, outcome: string, durationMs: number, scenarioId = DEFAULT_SCENARIO) =>
  ({ type: 'AGENT_COMPLETED' as const, scenarioId, occurredAt: now(), agentId, role, outcome, durationMs });

export const channelMessage = (agentId: string, role: string, content: string, scenarioId = DEFAULT_SCENARIO) =>
  ({ type: 'CHANNEL_MESSAGE' as const, scenarioId, occurredAt: now(), agentId, role, content });

export const gatePending = (gateId: string, agentId: string, action: string, classification: string, priorAgents: string, scenarioId = DEFAULT_SCENARIO) =>
  ({ type: 'GATE_PENDING' as const, scenarioId, occurredAt: now(), gateId, agentId, action, classification, priorAgents });

export const gateResolved = (gateId: string, decision: 'approved' | 'rejected', scenarioId = DEFAULT_SCENARIO) =>
  ({ type: 'GATE_RESOLVED' as const, scenarioId, occurredAt: now(), gateId, decision });

export const commitmentUpdated = (agentId: string, commitmentId: string, state: string, outcome: string, scenarioId = DEFAULT_SCENARIO) =>
  ({ type: 'COMMITMENT_UPDATED' as const, scenarioId, occurredAt: now(), agentId, commitmentId, state, outcome });
