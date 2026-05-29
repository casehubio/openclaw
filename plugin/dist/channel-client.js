export class ChannelClient {
    baseUrl;
    timeoutMs;
    constructor(baseUrl, timeoutMs) {
        this.baseUrl = baseUrl;
        this.timeoutMs = timeoutMs;
    }
    async getContext(agentId, since) {
        const url = `${this.baseUrl}/channel-context/${encodeURIComponent(agentId)}?since=${since}`;
        const controller = new AbortController();
        const timer = setTimeout(() => controller.abort(), this.timeoutMs);
        try {
            const res = await fetch(url, { signal: controller.signal });
            if (!res.ok)
                throw new Error(`HTTP ${res.status} from ${url}`);
            return (await res.json());
        }
        finally {
            clearTimeout(timer);
        }
    }
}
//# sourceMappingURL=channel-client.js.map