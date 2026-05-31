// plugin/src/commitment-manager.ts
//
// Manages the auto-commit lifecycle for CaseHub:
//   before_tool_call → open one commitment per turn (if autoCommit: true)
//   agent_end        → close open commitment
//   session_start    → inject any orphaned open commitments from prior sessions
//
// Calls /openclaw/plugin/* REST endpoints on the Quarkus app — NOT the MCP
// endpoint. Plugin hooks run outside the LLM turn; MCP is LLM-facing only.

import type { AgentEndEvent, HookResult, SessionStartEvent, ToolCallEvent } from "./types.js";

interface OpenCommitment {
  readonly commitmentId: string;
  readonly watchdogDeadline: string;
}

interface CommitmentListResponse {
  open: Array<{
    commitmentId: string;
    state: string;
    deadline: string;
    watchdogArmed: boolean;
  }>;
  count: number;
}

// Tools that must NOT trigger auto-commit:
// - casehub_status: read-only query, no obligation
// - casehub_commit and all lifecycle tools: auto-committing when the LLM is
//   explicitly managing commitments would create duplicate/recursive commitments
const AUTO_COMMIT_EXCLUDED_TOOLS = new Set([
  "casehub_status",
  "casehub_commit",
  "casehub_done",
  "casehub_reject",
  "casehub_checkpoint",
  "casehub_escalate",
  "casehub_queue",    // routing only, no direct obligation on the calling agent
]);
const ESCALATE_TOOL = "casehub_escalate";

/**
 * Manages per-turn commitment tracking for the auto-commit feature.
 *
 * autoCommit=true: opens one commitment at the first before_tool_call in a turn,
 * closes it at agent_end. Escalation clears the commitment without closing it
 * (the Watchdog continues for the escalation target).
 *
 * autoCommit=false: no automatic commit/done; session_start injection still works.
 */
export class CommitmentManager {
  private readonly baseUrl: string;
  private readonly timeoutMs: number;
  private readonly autoCommit: boolean;

  // agentId → open commitment for current turn; undefined = no open commitment
  private readonly turnCommitments = new Map<string, OpenCommitment>();

  constructor(baseUrl: string, timeoutMs: number, autoCommit: boolean) {
    this.baseUrl = baseUrl.replace(/\/$/, "");
    this.timeoutMs = timeoutMs;
    this.autoCommit = autoCommit;
  }

  async onBeforeToolCall(event: ToolCallEvent): Promise<void> {
    if (event.toolName === ESCALATE_TOOL) {
      // Escalation: remove from map so agent_end does not auto-close.
      // The Watchdog continues for the escalation target.
      this.turnCommitments.delete(event.agentId);
      return;
    }

    if (!this.autoCommit) return;
    if (AUTO_COMMIT_EXCLUDED_TOOLS.has(event.toolName)) return;
    if (this.turnCommitments.has(event.agentId)) return; // already open this turn

    try {
      const response = await this.post<OpenCommitment>("/openclaw/plugin/commit", {
        agentId: event.agentId,
        task: `agent turn — tool: ${event.toolName}`,
      });
      this.turnCommitments.set(event.agentId, response);
    } catch (err) {
      console.warn(`[casehub-openclaw] auto-commit failed for ${event.agentId}: ${err}`);
      // Fail open — agent turn proceeds without commitment
    }
  }

  async onAgentEnd(event: AgentEndEvent): Promise<void> {
    const commitment = this.turnCommitments.get(event.agentId);
    if (!commitment) return;

    // Always clear — even if done call fails, the next turn starts fresh
    this.turnCommitments.delete(event.agentId);

    try {
      await this.post("/openclaw/plugin/done", {
        agentId: event.agentId,
        commitmentId: commitment.commitmentId,
      });
    } catch (err) {
      console.warn(
        `[casehub-openclaw] auto-done failed for ${event.agentId} commitment ${commitment.commitmentId}: ${err}`,
      );
      // Logged: the Watchdog will fire and surface the unresolved commitment
    }
  }

  async onSessionStart(event: SessionStartEvent): Promise<HookResult> {
    try {
      const data = await this.get<CommitmentListResponse>(
        `/openclaw/plugin/commitments/${encodeURIComponent(event.agentId)}`,
      );

      if (data.count === 0) return {};

      const lines = data.open.map(
        (c) =>
          `- ${c.commitmentId}: state=${c.state}, deadline=${c.deadline}${c.watchdogArmed ? " (Watchdog armed)" : ""}`,
      );

      const text =
        `## Open CaseHub Commitments\n\n` +
        `You have ${data.count} open commitment(s) from a previous session:\n\n` +
        lines.join("\n") +
        `\n\nAddress before beginning new work: ` +
        `call \`casehub_done\` if complete, \`casehub_checkpoint\` if in progress, ` +
        `or \`casehub_reject\` if it cannot be completed.`;

      return { appendSystemContext: text };
    } catch (err) {
      console.warn(`[casehub-openclaw] session_start commitment query failed for ${event.agentId}: ${err}`);
      return {};
    }
  }

  private async post<T>(path: string, body: unknown): Promise<T> {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.timeoutMs);
    try {
      const resp = await fetch(`${this.baseUrl}${path}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
        signal: controller.signal,
      });
      if (!resp.ok) {
        throw new Error(`HTTP ${resp.status} from ${path}`);
      }
      return resp.json() as Promise<T>;
    } finally {
      clearTimeout(timer);
    }
  }

  private async get<T>(path: string): Promise<T> {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.timeoutMs);
    try {
      const resp = await fetch(`${this.baseUrl}${path}`, {
        signal: controller.signal,
      });
      if (!resp.ok) {
        throw new Error(`HTTP ${resp.status} from ${path}`);
      }
      return resp.json() as Promise<T>;
    } finally {
      clearTimeout(timer);
    }
  }
}
