import type { OpenClawPluginApi } from "./types.js";
export declare class ChannelContextPlugin {
    private readonly client;
    private readonly cursors;
    constructor(baseUrl: string, timeoutMs: number);
    register(api: OpenClawPluginApi): void;
    private _inject;
}
export declare function register(api: OpenClawPluginApi): void;
