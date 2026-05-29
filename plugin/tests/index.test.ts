// plugin/tests/index.test.ts
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { ChannelContextPlugin } from "../src/index.js";
import type { OpenClawPluginApi, PluginHookContext, HookResult } from "../src/types.js";

// ---------------------------------------------------------------------------
// Test infrastructure: capture the registered handler by mocking the plugin API
// ---------------------------------------------------------------------------
function makeApi(config?: Record<string, unknown>): {
  api: OpenClawPluginApi;
  callHook: (ctx: PluginHookContext) => Promise<HookResult>;
} {
  let capturedHandler: ((ctx: PluginHookContext) => Promise<HookResult>) | undefined;
  const api: OpenClawPluginApi = {
    on(_event, handler) { capturedHandler = handler; },
    config: config as OpenClawPluginApi["config"],
  };
  return {
    api,
    callHook: (ctx) => {
      if (!capturedHandler) throw new Error("handler not registered");
      return capturedHandler(ctx);
    },
  };
}

// ---------------------------------------------------------------------------
// Helpers for mocking fetch
// ---------------------------------------------------------------------------
const mockFetch = vi.fn();
vi.stubGlobal("fetch", mockFetch);
beforeEach(() => mockFetch.mockReset());
afterEach(() => vi.useRealTimers());

function mockOkResponse(body: object): void {
  mockFetch.mockResolvedValueOnce(new Response(JSON.stringify(body), { status: 200 }));
}

const CTX: PluginHookContext = { agentId: "home-agent", sessionKey: "main" };

const BASE = {
  messages: [] as object[],
  lastEvictionWindowSeq: -1,
  lastWindowSeq: 0,
  currentWindowSeq: 100,
  agentHasAssociation: true,
  lastChannelActivity: "2026-05-29T10:00:00Z",
};

const ONE_MESSAGE = {
  windowSeq: 42,
  channelId: "uuid-1",
  channelName: "household/observe",
  messageType: "EVENT",
  senderId: "finance-agent",
  correlationId: null,
  content: "Budget exhausted.",
  receivedAt: "2026-05-29T10:00:00Z",
};

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------
describe("ChannelContextPlugin._inject", () => {
  it("returns {} when agentHasAssociation is false", async () => {
    mockOkResponse({ ...BASE, agentHasAssociation: false });
    const { api, callHook } = makeApi();
    const plugin = new ChannelContextPlugin("http://localhost:8080", 3000);
    plugin.register(api);
    const result = await callHook(CTX);
    expect(result).toEqual({});
  });

  it("returns {} and resets cursor on service restart (since > currentWindowSeq)", async () => {
    // Prime the cursor: first turn sets cursor to 50
    mockOkResponse({ ...BASE, messages: [ONE_MESSAGE], lastWindowSeq: 50, currentWindowSeq: 100 });
    const { api, callHook } = makeApi();
    const plugin = new ChannelContextPlugin("http://localhost:8080", 3000);
    plugin.register(api);
    await callHook(CTX); // cursor = 50

    // Second turn: service restarted — currentWindowSeq (5) < since (50)
    mockOkResponse({ ...BASE, currentWindowSeq: 5, lastWindowSeq: 5 });
    const result = await callHook(CTX);
    expect(result).toEqual({});

    // Third turn: cursor was reset to 0, so fetch uses since=0
    mockOkResponse({ ...BASE, messages: [ONE_MESSAGE], lastWindowSeq: 42 });
    const result3 = await callHook(CTX);
    expect(result3.appendSystemContext).toBeDefined();
    const fetchedUrl = mockFetch.mock.calls[2][0] as string;
    expect(fetchedUrl).toContain("since=0");
  });

  it("injects formatted messages when messages are present", async () => {
    mockOkResponse({ ...BASE, messages: [ONE_MESSAGE], lastWindowSeq: 42 });
    const { api, callHook } = makeApi();
    const plugin = new ChannelContextPlugin("http://localhost:8080", 3000);
    plugin.register(api);
    const result = await callHook(CTX);
    expect(result.appendSystemContext).toContain("## Channel Context");
    expect(result.appendSystemContext).toContain("**finance-agent**");
    expect(result.appendSystemContext).toContain("Budget exhausted.");
  });

  it("injects overflow notice AND messages (additive) when both apply", async () => {
    // lastEvictionWindowSeq (10) > since (0): overflow occurred
    // messages also present
    mockOkResponse({
      ...BASE,
      messages: [ONE_MESSAGE],
      lastEvictionWindowSeq: 10,
      lastWindowSeq: 42,
    });
    const { api, callHook } = makeApi();
    const plugin = new ChannelContextPlugin("http://localhost:8080", 3000);
    plugin.register(api);
    const result = await callHook(CTX);
    expect(result.appendSystemContext).toContain("evicted");
    expect(result.appendSystemContext).toContain("Budget exhausted.");
  });

  it("injects overflow notice only when eviction occurred but messages is empty", async () => {
    mockOkResponse({
      ...BASE,
      messages: [],
      lastEvictionWindowSeq: 10,
      lastWindowSeq: 0,
    });
    const { api, callHook } = makeApi();
    const plugin = new ChannelContextPlugin("http://localhost:8080", 3000);
    plugin.register(api);
    const result = await callHook(CTX);
    expect(result.appendSystemContext).toContain("evicted");
    // No idle notice (would contradict the overflow signal)
    expect(result.appendSystemContext).not.toContain("No channel activity");
  });

  it("injects idle notice with elapsed time when no messages and no eviction", async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-05-29T12:30:00Z"));
    mockOkResponse({
      ...BASE,
      messages: [],
      lastEvictionWindowSeq: -1,
      lastChannelActivity: "2026-05-29T11:00:00Z", // 90 minutes ago
    });
    const { api, callHook } = makeApi();
    const plugin = new ChannelContextPlugin("http://localhost:8080", 3000);
    plugin.register(api);
    const result = await callHook(CTX);
    expect(result.appendSystemContext).toContain("No channel activity in the last 90 minute(s).");
  });

  it("injects 'no activity recorded' idle notice when lastChannelActivity is epoch", async () => {
    mockOkResponse({
      ...BASE,
      messages: [],
      lastEvictionWindowSeq: -1,
      lastChannelActivity: "1970-01-01T00:00:00Z",
    });
    const { api, callHook } = makeApi();
    const plugin = new ChannelContextPlugin("http://localhost:8080", 3000);
    plugin.register(api);
    const result = await callHook(CTX);
    expect(result.appendSystemContext).toContain("No channel activity recorded for this agent yet.");
  });

  it("returns {} and logs warning on HTTP error (fail open)", async () => {
    mockFetch.mockResolvedValueOnce(new Response(null, { status: 503 }));
    const warnSpy = vi.spyOn(console, "warn").mockImplementation(() => undefined);
    const { api, callHook } = makeApi();
    const plugin = new ChannelContextPlugin("http://localhost:8080", 3000);
    plugin.register(api);
    const result = await callHook(CTX);
    expect(result).toEqual({});
    expect(warnSpy).toHaveBeenCalledWith(expect.stringContaining("[casehub-openclaw]"));
    warnSpy.mockRestore();
  });

  it("returns {} and logs warning on network timeout (fail open)", async () => {
    mockFetch.mockRejectedValueOnce(new DOMException("Aborted", "AbortError"));
    const warnSpy = vi.spyOn(console, "warn").mockImplementation(() => undefined);
    const { api, callHook } = makeApi();
    const plugin = new ChannelContextPlugin("http://localhost:8080", 3000);
    plugin.register(api);
    const result = await callHook(CTX);
    expect(result).toEqual({});
    expect(warnSpy).toHaveBeenCalledWith(expect.stringContaining("[casehub-openclaw]"));
    warnSpy.mockRestore();
  });

  it("resets cursor to 0 when sessionKey changes (new session for same agent)", async () => {
    // Turn 1: session-A, cursor advances to 42
    mockOkResponse({ ...BASE, messages: [ONE_MESSAGE], lastWindowSeq: 42 });
    const { api, callHook } = makeApi();
    const plugin = new ChannelContextPlugin("http://localhost:8080", 3000);
    plugin.register(api);
    await callHook({ agentId: "home-agent", sessionKey: "session-A" });

    // Turn 2: session-B (daily reset) — cursor should reset to 0
    mockOkResponse({ ...BASE, messages: [ONE_MESSAGE], lastWindowSeq: 10 });
    await callHook({ agentId: "home-agent", sessionKey: "session-B" });

    const secondCallUrl = mockFetch.mock.calls[1][0] as string;
    expect(secondCallUrl).toContain("since=0");
  });
});
