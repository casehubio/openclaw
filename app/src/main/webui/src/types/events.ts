interface BaseEvent {
  readonly scenarioId: string;
  readonly occurredAt: string;   // ISO-8601
}

export interface ScenarioStartedEvent extends BaseEvent { readonly type: 'SCENARIO_STARTED'; }
export interface ScenarioCompletedEvent extends BaseEvent { readonly type: 'SCENARIO_COMPLETED'; }
export interface ScenarioFailedEvent extends BaseEvent { readonly type: 'SCENARIO_FAILED'; readonly error: string; }

export interface AgentStartedEvent extends BaseEvent {
  readonly type: 'AGENT_STARTED';
  readonly agentId: string;
  readonly role: string;
}
export interface AgentCompletedEvent extends BaseEvent {
  readonly type: 'AGENT_COMPLETED';
  readonly agentId: string;
  readonly role: string;
  readonly outcome: 'completed' | 'failed' | 'declined' | 'delegated' | 'timeout';
  readonly durationMs: number;
}

export interface CommitmentUpdatedEvent extends BaseEvent {
  readonly type: 'COMMITMENT_UPDATED';
  readonly agentId: string;
  readonly commitmentId: string;
  readonly state: string;
  readonly outcome: string;
}

export interface ChannelMessageEvent extends BaseEvent {
  readonly type: 'CHANNEL_MESSAGE';
  readonly agentId: string;
  readonly role: string;
  readonly content: string;
}

export interface GatePendingEvent extends BaseEvent {
  readonly type: 'GATE_PENDING';
  readonly gateId: string;
  readonly agentId: string;
  readonly action: string;
  readonly classification: string;
  readonly priorAgents: string;
}

export interface GateResolvedEvent extends BaseEvent {
  readonly type: 'GATE_RESOLVED';
  readonly gateId: string;
  readonly decision: 'approved' | 'rejected';
}

export type CaseExecutionEvent =
  | ScenarioStartedEvent | ScenarioCompletedEvent | ScenarioFailedEvent
  | AgentStartedEvent | AgentCompletedEvent
  | CommitmentUpdatedEvent | ChannelMessageEvent
  | GatePendingEvent | GateResolvedEvent;

export interface AgentState {
  readonly agentId: string;
  readonly role: string;
  readonly state: 'waiting' | 'running' | 'completed' | 'failed' | 'declined' | 'delegated' | 'timeout';
  readonly durationMs: number | null;
}

export interface GateState {
  readonly gateId: string;
  readonly agentId: string;
  readonly action: string;
  readonly classification: string;
  readonly priorAgents: string;
}

export interface ScenarioStateSnapshot {
  readonly scenarioId: string;
  readonly status: 'idle' | 'running' | 'completed' | 'failed';
  readonly agents: AgentState[];
  readonly pendingGate: GateState | null;
  readonly recentMessages: ChannelMessageEvent[];
}

export interface ScenarioDef {
  readonly id: string;
  readonly name: string;
  readonly description: string;
  readonly status: 'idle' | 'running' | 'completed' | 'failed';
}
