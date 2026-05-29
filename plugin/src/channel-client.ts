// plugin/src/channel-client.ts
import type { WindowContent } from "./types.js";

export class ChannelClient {
  constructor(private readonly baseUrl: string, private readonly timeoutMs: number) {}

  async getContext(agentId: string, since: number): Promise<WindowContent> {
    const url =
      `${this.baseUrl}/channel-context/${encodeURIComponent(agentId)}?since=${since}`;
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.timeoutMs);
    try {
      const res = await fetch(url, { signal: controller.signal });
      if (!res.ok) throw new Error(`HTTP ${res.status} from ${url}`);
      return (await res.json()) as WindowContent;
    } finally {
      clearTimeout(timer);
    }
  }
}
