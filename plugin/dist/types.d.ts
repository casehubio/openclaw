export interface ContextMessage {
    windowSeq: number;
    channelId: string;
    channelName: string | null;
    messageType: string;
    senderId: string;
    correlationId: string | null;
    content: string;
    receivedAt: string;
}
export interface WindowContent {
    messages: ContextMessage[];
    lastEvictionWindowSeq: number;
    lastWindowSeq: number;
    currentWindowSeq: number;
    agentHasAssociation: boolean;
    lastChannelActivity: string;
}
export interface PluginConfig {
    baseUrl?: string;
    timeoutMs?: number;
}
export interface PluginHookContext {
    agentId: string;
    sessionKey: string;
    channelId?: string;
}
export interface HookResult {
    appendSystemContext?: string;
}
export interface OpenClawPluginApi {
    on(event: "before_prompt_build", handler: (ctx: PluginHookContext) => Promise<HookResult>): void;
    config?: PluginConfig;
}
