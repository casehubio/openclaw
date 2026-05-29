import type { WindowContent } from "./types.js";
export declare class ChannelClient {
    private readonly baseUrl;
    private readonly timeoutMs;
    constructor(baseUrl: string, timeoutMs: number);
    getContext(agentId: string, since: number): Promise<WindowContent>;
}
