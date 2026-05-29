// plugin/tests/channel-client.test.ts
import { describe, it, expect, vi, beforeEach } from "vitest";
import { ChannelClient } from "../src/channel-client.js";

const BASE_CONTENT = {
  messages: [],
  lastEvictionWindowSeq: -1,
  lastWindowSeq: 0,
  currentWindowSeq: 100,
  agentHasAssociation: true,
  lastChannelActivity: "2026-05-29T10:00:00Z",
};

const mockFetch = vi.fn();
vi.stubGlobal("fetch", mockFetch);

beforeEach(() => { mockFetch.mockReset(); });

describe("ChannelClient.getContext", () => {
  it("returns parsed WindowContent on successful response", async () => {
    mockFetch.mockResolvedValueOnce(
      new Response(JSON.stringify(BASE_CONTENT), { status: 200 })
    );
    const client = new ChannelClient("http://localhost:8080", 3000);
    const result = await client.getContext("home-agent", 0);
    expect(result.agentHasAssociation).toBe(true);
    expect(result.currentWindowSeq).toBe(100);
  });

  it("throws on non-2xx response", async () => {
    mockFetch.mockResolvedValueOnce(new Response(null, { status: 503 }));
    const client = new ChannelClient("http://localhost:8080", 3000);
    await expect(client.getContext("home-agent", 0)).rejects.toThrow("HTTP 503");
  });

  it("throws AbortError on timeout", async () => {
    // Simulate AbortController.abort() firing
    mockFetch.mockRejectedValueOnce(new DOMException("Aborted", "AbortError"));
    const client = new ChannelClient("http://localhost:8080", 1); // 1ms timeout
    await expect(client.getContext("home-agent", 0)).rejects.toThrow();
  });

  it("forwards the since query parameter", async () => {
    mockFetch.mockResolvedValueOnce(
      new Response(JSON.stringify(BASE_CONTENT), { status: 200 })
    );
    const client = new ChannelClient("http://localhost:8080", 3000);
    await client.getContext("home-agent", 42);
    const calledUrl = mockFetch.mock.calls[0][0] as string;
    expect(calledUrl).toContain("since=42");
  });

  it("URL-encodes agentId containing special characters", async () => {
    mockFetch.mockResolvedValueOnce(
      new Response(JSON.stringify(BASE_CONTENT), { status: 200 })
    );
    const client = new ChannelClient("http://localhost:8080", 3000);
    await client.getContext("agent/with/slash", 0);
    const calledUrl = mockFetch.mock.calls[0][0] as string;
    expect(calledUrl).toContain("agent%2Fwith%2Fslash");
    expect(calledUrl).not.toContain("agent/with/slash");
  });
});
