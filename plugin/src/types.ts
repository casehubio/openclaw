// plugin/src/types.ts

// ChannelContextWindow REST response types — mirror Java WindowContent / ContextMessage records

export interface ContextMessage {
  windowSeq: number;
  channelId: string;
  channelName: string | null;
  messageType: string;           // Qhorus MessageType name: "EVENT", "COMMAND", "STATUS", etc.
  senderId: string;
  correlationId: string | null;
  content: string;
  receivedAt: string;            // ISO-8601
}

export interface WindowContent {
  messages: ContextMessage[];
  lastEvictionWindowSeq: number; // -1 if no eviction
  lastWindowSeq: number;
  currentWindowSeq: number;
  agentHasAssociation: boolean;
  lastChannelActivity: string;   // ISO-8601; epoch sentinel = "1970-01-01T00:00:00Z"
}

// OpenClaw Plugin SDK interfaces — structurally assumed from documented plugin examples.
// api.config field name is provisional; verify against upstream types when available.

export interface PluginConfig {
  baseUrl?: string;
  timeoutMs?: number;
  casehub?: {
    autoCommit?: boolean;
  };
}

export interface PluginHookContext {
  agentId: string;
  sessionKey: string;
  channelId?: string;
}

export interface HookResult {
  appendSystemContext?: string;
}

/** Event payload for before_tool_call — tool name + params before execution. */
export interface ToolCallEvent {
  agentId: string;
  sessionKey: string;
  toolName: string;
  toolCallId: string;
  params: Record<string, unknown>;
}

/** Event payload for agent_end — turn completion summary. */
export interface AgentEndEvent {
  agentId: string;
  sessionKey: string;
  success: boolean;
  durationMs: number;
}

/** Event payload for session_start — new session or session resume. */
export interface SessionStartEvent {
  agentId: string;
  sessionKey: string;
}

export interface OpenClawPluginApi {
  on(
    event: "before_prompt_build",
    handler: (ctx: PluginHookContext) => Promise<HookResult>,
  ): void;
  on(
    event: "before_tool_call",
    handler: (event: ToolCallEvent) => Promise<void>,
  ): void;
  on(
    event: "agent_end",
    handler: (event: AgentEndEvent) => Promise<void>,
  ): void;
  on(
    event: "session_start",
    handler: (event: SessionStartEvent) => Promise<HookResult>,
  ): void;
  // PROVISIONAL: "config" field name assumed from existing plugin examples.
  // If OpenClaw publishes a types package, verify this field name.
  config?: PluginConfig;
}
