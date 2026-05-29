// plugin/tests/formatters.test.ts
import { describe, it, expect, vi, afterEach } from "vitest";
import { formatMessages, formatIdle } from "../src/formatters.js";
import type { ContextMessage } from "../src/types.js";

const MESSAGE: ContextMessage = {
  windowSeq: 1,
  channelId: "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  channelName: "household/observe",
  messageType: "EVENT",
  senderId: "finance-agent",
  correlationId: null,
  content: "Monthly budget exhausted.",
  receivedAt: "2026-05-29T10:15:00Z",
};

describe("formatMessages", () => {
  it("formats a single message with UTC time, channel name, and speech act type", () => {
    const result = formatMessages([MESSAGE]);
    expect(result).toBe(
      "**finance-agent** on `household/observe` [EVENT] at 10:15Z:\nMonthly budget exhausted."
    );
  });

  it("joins multiple messages with double newline", () => {
    const second: ContextMessage = { ...MESSAGE, windowSeq: 2, content: "Second message." };
    const result = formatMessages([MESSAGE, second]);
    expect(result).toContain("Monthly budget exhausted.\n\n");
    // Both messages present
    expect(result.split("\n\n")).toHaveLength(2);
  });

  it("falls back to channelId when channelName is null", () => {
    const noName: ContextMessage = { ...MESSAGE, channelName: null };
    const result = formatMessages([noName]);
    expect(result).toContain("`3fa85f64-5717-4562-b3fc-2c963f66afa6`");
  });

  it("renders receivedAt as UTC HH:MMZ", () => {
    const result = formatMessages([MESSAGE]);
    expect(result).toContain("10:15Z");
    // Verify the format — not local time
    expect(result).not.toMatch(/\d{1,2}:\d{2}(?!Z)/); // no time without trailing Z
  });
});

describe("formatIdle", () => {
  afterEach(() => { vi.useRealTimers(); });

  it("returns 'no activity recorded' for epoch sentinel string", () => {
    expect(formatIdle("1970-01-01T00:00:00Z")).toBe(
      "No channel activity recorded for this agent yet."
    );
  });

  it("returns 'no activity recorded' for epoch with millisecond precision", () => {
    // Jackson may produce either format depending on version
    expect(formatIdle("1970-01-01T00:00:00.000Z")).toBe(
      "No channel activity recorded for this agent yet."
    );
  });

  it("returns elapsed minutes from real lastChannelActivity", () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-05-29T12:30:00Z"));
    // 90 minutes before the fixed "now"
    const result = formatIdle("2026-05-29T11:00:00Z");
    expect(result).toBe("No channel activity in the last 90 minute(s).");
  });
});
