// plugin/src/index.ts
import { ChannelClient } from "./channel-client.js";
import { formatMessages, formatIdle } from "./formatters.js";
export class ChannelContextPlugin {
    client;
    // Cursor keyed by agentId only — bounded by distinct agents, not sessions.
    // Stores sessionKey alongside cursor; resets to 0 when sessionKey changes
    // (OpenClaw resets sessions daily and on idle timeout).
    cursors = new Map();
    constructor(baseUrl, timeoutMs) {
        this.client = new ChannelClient(baseUrl, timeoutMs);
    }
    // Synchronous — OpenClaw snapshots hooks at plugin registration time.
    // Registering from start() (async, post-gateway) silently misses the window.
    register(api) {
        api.on("before_prompt_build", (ctx) => this._inject(ctx));
    }
    async _inject(ctx) {
        const entry = this.cursors.get(ctx.agentId);
        // Reset cursor when sessionKey changes — new session gets full window from buffer
        const since = (entry?.sessionKey === ctx.sessionKey) ? (entry?.cursor ?? 0) : 0;
        let result;
        try {
            result = await this.client.getContext(ctx.agentId, since);
        }
        catch (err) {
            // Fail open — agent turn must never be blocked by context unavailability
            console.warn(`[casehub-openclaw] context fetch failed for ${ctx.agentId}: ${err}`);
            return {};
        }
        // agentHasAssociation=false covers both: agent not yet wired AND service restarted
        // before re-registration. After re-registration, agentHasAssociation=true and
        // currentWindowSeq will be low, triggering the restart detection below.
        if (!result.agentHasAssociation)
            return {};
        // Service restart: currentWindowSeq reset below our cursor.
        // Skip this turn; next turn will call with since=0 and get a fresh window.
        if (since > result.currentWindowSeq) {
            this.cursors.set(ctx.agentId, { cursor: 0, sessionKey: ctx.sessionKey });
            return {};
        }
        const parts = [];
        // Overflow notice — additive with messages, not exclusive.
        // Edge case: if eviction occurred AND all remaining messages also expired (messages=[]),
        // the overflow notice still fires but no idle notice is injected — the empty messages
        // array implicitly signals no current content. A combined notice is not warranted.
        if (result.lastEvictionWindowSeq > since) {
            parts.push("⚠️ Some channel messages were evicted before this turn (high volume). " +
                "Full history is available in the CaseHub audit ledger.");
        }
        // Available messages — always injected when present
        if (result.messages.length > 0) {
            parts.push(formatMessages(result.messages));
        }
        // Idle notice — only when no messages AND no relevant eviction.
        // Injecting idle alongside overflow would be contradictory.
        if (result.messages.length === 0 && result.lastEvictionWindowSeq <= since) {
            parts.push(formatIdle(result.lastChannelActivity));
        }
        // Advance cursor. All non-early-return paths produce at least one part
        // (overflow OR messages OR idle are mutually covering), so parts.length===0
        // is unreachable in practice. Guard kept for defensive correctness.
        this.cursors.set(ctx.agentId, { cursor: result.lastWindowSeq, sessionKey: ctx.sessionKey });
        if (parts.length === 0)
            return {};
        return { appendSystemContext: "## Channel Context\n\n" + parts.join("\n\n") };
    }
}
export function register(api) {
    const cfg = api.config ?? {};
    new ChannelContextPlugin(cfg.baseUrl ?? "http://localhost:8080", cfg.timeoutMs ?? 3000).register(api);
}
//# sourceMappingURL=index.js.map