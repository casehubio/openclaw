// plugin/tests/commitment-manager.test.ts
import { describe, it, expect, vi, beforeEach } from "vitest";
import { CommitmentManager } from "../src/commitment-manager.js";
import type { AgentEndEvent, SessionStartEvent, ToolCallEvent } from "../src/types.js";

// ---------------------------------------------------------------------------
// Mock fetch globally
// ---------------------------------------------------------------------------
const mockFetch = vi.fn();
vi.stubGlobal("fetch", mockFetch);
beforeEach(() => mockFetch.mockReset());

function mockOk(body: object = {}): void {
  mockFetch.mockResolvedValueOnce(new Response(JSON.stringify(body), { status: 200 }));
}

function mockError(status = 503): void {
  mockFetch.mockResolvedValueOnce(new Response(null, { status }));
}

const BASE_URL = "http://localhost:8080";
const AGENT = "finance-agent";

// ---------------------------------------------------------------------------
// before_tool_call: auto-commit
// ---------------------------------------------------------------------------
describe("CommitmentManager.onBeforeToolCall", () => {
  it("opens a commitment when autoCommit is true and no commitment is open", async () => {
    mockOk({ commitmentId: "c-abc123", watchdogDeadline: "2026-06-01T17:00:00Z" });

    const mgr = new CommitmentManager(BASE_URL, 3000, true);
    const event: ToolCallEvent = {
      agentId: AGENT,
      sessionKey: "main",
      toolName: "run_report",
      toolCallId: "tc-1",
      params: {},
    };
    await mgr.onBeforeToolCall(event);

    expect(mockFetch).toHaveBeenCalledOnce();
    const [url, init] = mockFetch.mock.calls[0] as [string, RequestInit];
    expect(url).toBe(`${BASE_URL}/openclaw/plugin/commit`);
    expect(init.method).toBe("POST");
    const body = JSON.parse(init.body as string) as unknown;
    expect(body).toMatchObject({ agentId: AGENT });
  });

  it("skips commit when autoCommit is false", async () => {
    const mgr = new CommitmentManager(BASE_URL, 3000, false);
    await mgr.onBeforeToolCall({
      agentId: AGENT, sessionKey: "main", toolName: "run_report", toolCallId: "tc-1", params: {},
    });
    expect(mockFetch).not.toHaveBeenCalled();
  });

  it("skips commit for CaseHub lifecycle tools (casehub_done, casehub_reject, etc.)", async () => {
    const mgr = new CommitmentManager(BASE_URL, 3000, true);
    for (const toolName of ["casehub_done", "casehub_reject", "casehub_checkpoint", "casehub_commit", "casehub_queue"]) {
      await mgr.onBeforeToolCall({
        agentId: AGENT, sessionKey: "main", toolName, toolCallId: "tc-1", params: {},
      });
    }
    expect(mockFetch).not.toHaveBeenCalled();
  });

  it("skips commit when a commitment is already open for this agent turn", async () => {
    mockOk({ commitmentId: "c-first", watchdogDeadline: "2026-06-01T17:00:00Z" });
    const mgr = new CommitmentManager(BASE_URL, 3000, true);

    await mgr.onBeforeToolCall({
      agentId: AGENT, sessionKey: "main", toolName: "run_report", toolCallId: "tc-1", params: {},
    });
    await mgr.onBeforeToolCall({
      agentId: AGENT, sessionKey: "main", toolName: "send_email", toolCallId: "tc-2", params: {},
    });

    // Only one commit call — the second tool call in the same turn skips
    expect(mockFetch).toHaveBeenCalledOnce();
  });

  it("clears the commitment when casehub_escalate is called", async () => {
    mockOk({ commitmentId: "c-abc123", watchdogDeadline: "2026-06-01T17:00:00Z" });
    const mgr = new CommitmentManager(BASE_URL, 3000, true);

    await mgr.onBeforeToolCall({
      agentId: AGENT, sessionKey: "main", toolName: "run_report", toolCallId: "tc-1", params: {},
    });

    await mgr.onBeforeToolCall({
      agentId: AGENT, sessionKey: "main", toolName: "casehub_escalate", toolCallId: "tc-2", params: {},
    });

    // After escalation, commitment is cleared — agent_end should NOT call done
    mockFetch.mockReset();
    await mgr.onAgentEnd({
      agentId: AGENT, sessionKey: "main", success: true, durationMs: 100,
    });
    expect(mockFetch).not.toHaveBeenCalled();
  });

  it("fails open when Quarkus is unavailable — agent turn continues", async () => {
    mockError(503);
    const warnSpy = vi.spyOn(console, "warn").mockImplementation(() => undefined);
    const mgr = new CommitmentManager(BASE_URL, 3000, true);

    // Should not throw
    await expect(mgr.onBeforeToolCall({
      agentId: AGENT, sessionKey: "main", toolName: "run_report", toolCallId: "tc-1", params: {},
    })).resolves.toBeUndefined();

    expect(warnSpy).toHaveBeenCalledWith(expect.stringContaining("[casehub-openclaw]"));
    warnSpy.mockRestore();
  });
});

// ---------------------------------------------------------------------------
// agent_end: auto-done
// ---------------------------------------------------------------------------
describe("CommitmentManager.onAgentEnd", () => {
  it("calls done when a commitment is open for the agent", async () => {
    // Setup: open a commitment
    mockOk({ commitmentId: "c-abc123", watchdogDeadline: "2026-06-01T17:00:00Z" });
    const mgr = new CommitmentManager(BASE_URL, 3000, true);
    await mgr.onBeforeToolCall({
      agentId: AGENT, sessionKey: "main", toolName: "run_report", toolCallId: "tc-1", params: {},
    });

    mockOk({ closed: true });
    const event: AgentEndEvent = { agentId: AGENT, sessionKey: "main", success: true, durationMs: 200 };
    await mgr.onAgentEnd(event);

    expect(mockFetch).toHaveBeenCalledTimes(2);
    const [url, init] = mockFetch.mock.calls[1] as [string, RequestInit];
    expect(url).toBe(`${BASE_URL}/openclaw/plugin/done`);
    expect(init.method).toBe("POST");
    const body = JSON.parse(init.body as string) as unknown;
    expect(body).toMatchObject({ agentId: AGENT, commitmentId: "c-abc123" });
  });

  it("is a no-op when no commitment is open", async () => {
    const mgr = new CommitmentManager(BASE_URL, 3000, true);
    await mgr.onAgentEnd({
      agentId: AGENT, sessionKey: "main", success: true, durationMs: 100,
    });
    expect(mockFetch).not.toHaveBeenCalled();
  });

  it("clears the commitment from the map after done, even if done call fails", async () => {
    mockOk({ commitmentId: "c-abc123", watchdogDeadline: "2026-06-01T17:00:00Z" });
    const mgr = new CommitmentManager(BASE_URL, 3000, true);
    await mgr.onBeforeToolCall({
      agentId: AGENT, sessionKey: "main", toolName: "run_report", toolCallId: "tc-1", params: {},
    });

    // Done call fails — commitment must still be cleared
    mockError(503);
    const warnSpy = vi.spyOn(console, "warn").mockImplementation(() => undefined);
    await mgr.onAgentEnd({ agentId: AGENT, sessionKey: "main", success: false, durationMs: 50 });
    warnSpy.mockRestore();

    // Next turn: should open a new commitment (map is cleared)
    mockFetch.mockReset();
    mockOk({ commitmentId: "c-new", watchdogDeadline: "2026-06-02T17:00:00Z" });
    await mgr.onBeforeToolCall({
      agentId: AGENT, sessionKey: "main", toolName: "run_report", toolCallId: "tc-3", params: {},
    });
    expect(mockFetch).toHaveBeenCalledOnce();
  });
});

// ---------------------------------------------------------------------------
// session_start: open commitment injection
// ---------------------------------------------------------------------------
describe("CommitmentManager.onSessionStart", () => {
  it("injects open commitments into context when they exist", async () => {
    mockOk({
      open: [
        {
          commitmentId: "c-abc123",
          state: "OPEN",
          deadline: "2026-06-01T17:00:00Z",
          watchdogArmed: true,
        },
      ],
      count: 1,
    });

    const mgr = new CommitmentManager(BASE_URL, 3000, false);
    const event: SessionStartEvent = { agentId: AGENT, sessionKey: "main" };
    const result = await mgr.onSessionStart(event);

    expect(result.appendSystemContext).toContain("open commitment");
    expect(result.appendSystemContext).toContain("c-abc123");
    expect(result.appendSystemContext).toContain("casehub_done");
  });

  it("returns empty context when no open commitments", async () => {
    mockOk({ open: [], count: 0 });
    const mgr = new CommitmentManager(BASE_URL, 3000, false);
    const result = await mgr.onSessionStart({ agentId: AGENT, sessionKey: "main" });

    expect(result.appendSystemContext).toBeUndefined();
  });

  it("fails open when Quarkus is unavailable", async () => {
    mockError(503);
    const warnSpy = vi.spyOn(console, "warn").mockImplementation(() => undefined);
    const mgr = new CommitmentManager(BASE_URL, 3000, false);

    await expect(mgr.onSessionStart({ agentId: AGENT, sessionKey: "main" }))
      .resolves.toEqual({});

    expect(warnSpy).toHaveBeenCalledWith(expect.stringContaining("[casehub-openclaw]"));
    warnSpy.mockRestore();
  });
});
