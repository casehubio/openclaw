# Epic 5 Design: Python SDK + TypeScript Plugin

**Issue:** casehubio/openclaw#5
**Branch:** `issue-5-python-sdk`
**Date:** 2026-05-29
**Status:** Approved — ready for implementation

---

## 1. Scope

Implements the `before_prompt_build` context injection for OpenClaw agents:

- **TypeScript plugin** (`plugin/`) — OpenClaw in-process plugin; registers the
  `before_prompt_build` hook; fetches from the ChannelContextWindow REST endpoint;
  injects recent Qhorus channel activity as `appendSystemContext`
- **Python client library** (`python/`) — synchronous HTTP client + Pydantic models;
  used by Python skill scripts that want to query channel context explicitly; no hook logic

**Not in scope:** OpenClaw skill pack (Epic 7). Python async support. Pluggable context
engine (deferred open question from research spec §12.10). Multiple OpenClaw instances
with shared cursor state.

---

## 2. Key Research Finding

OpenClaw's `before_prompt_build` hook is part of the TypeScript/JavaScript Plugin SDK.
The Python SDK (`from openclaw import OpenClawClient`) is an external app SDK with no
hook registration mechanism. All real-world OpenClaw plugins that use lifecycle hooks
are written in TypeScript.

The research spec's `@agent.on("before_prompt_build")` pseudocode was conceptual — not
a Python SDK call. The correct implementation is a TypeScript plugin.

**Evidence:** MemOS Cloud plugin, openclaw-observability-plugin, openclaw-sticky-context
(all TypeScript, all confirmed using `ctx.agentId` via `api.on("before_prompt_build")`).
OpenClaw issue #52411 confirms `PluginHookAgentContext` has `agentId`, `sessionKey`,
`channelId` fields. No Python plugin mechanism exists.

---

## 3. Architecture

Three independent build artifacts, three languages, three registries:

```
casehub-openclaw/
├── plugin/                        TypeScript — npm: casehub-openclaw-plugin
│   ├── openclaw.plugin.json
│   ├── package.json
│   ├── tsconfig.json
│   └── src/
│       ├── index.ts               register(api) — wires before_prompt_build hook
│       ├── channel-client.ts      fetch-based HTTP client
│       ├── formatters.ts          formatMessages, formatIdle (isolated for testing)
│       └── types.ts               all TypeScript interfaces
│   └── tests/
│       ├── index.test.ts          hook logic: all injection cases
│       ├── channel-client.test.ts HTTP client
│       └── formatters.test.ts     formatter unit tests
│
├── python/                        Python — PyPI: casehub-openclaw
│   ├── pyproject.toml             (trimmed: remove pytest-asyncio; add respx, build)
│   └── src/casehub_openclaw/
│       ├── __init__.py            exports: ChannelClient, WindowContent, ContextMessage
│       ├── models.py              Pydantic v2 models
│       └── channel_client.py      sync httpx client
│   └── tests/
│       ├── conftest.py
│       └── test_channel_client.py
│   (context_hook.py deleted — hook logic is TypeScript)
│
├── core/     )
├── casehub/  )  Existing Java modules — unchanged in this epic
└── app/      )
```

Maven does not reference `plugin/` or `python/` (protocol PP-20260525-724406).
Independent CI publish steps required for npm and PyPI (manual for this epic).

---

## 4. REST API Contract (Source of Truth)

`GET /channel-context/{agentId}?since={windowSeq}`

Always returns 200. Unknown `agentId` returns `noAssociation()` — not 404.
`since` defaults to 0 when omitted.

Response body (Java `WindowContent` record, camelCase JSON via Jackson):

```json
{
  "messages": [
    {
      "windowSeq": 42,
      "channelId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
      "channelName": "household/observe",
      "messageType": "EVENT",
      "senderId": "finance-agent",
      "correlationId": null,
      "content": "Monthly budget exhausted — essentials only.",
      "receivedAt": "2026-05-29T10:00:00Z"
    }
  ],
  "lastEvictionWindowSeq": -1,
  "lastWindowSeq": 42,
  "currentWindowSeq": 100,
  "agentHasAssociation": true,
  "lastChannelActivity": "2026-05-29T10:00:00Z"
}
```

Field semantics:
- `lastEvictionWindowSeq` — `-1` if no eviction; otherwise the `windowSeq` of the most
  recently evicted message across all associated channels
- `lastWindowSeq` — max `windowSeq` of returned messages; equals `since` if none returned
  (cursor only advances when new messages arrive)
- `currentWindowSeq` — service's `AtomicLong` at query time; used for restart detection
- `agentHasAssociation` — `false` if agentId unknown → caller skips silently
- `lastChannelActivity` — `"1970-01-01T00:00:00Z"` (`Instant.EPOCH`) if no messages ever

---

## 5. TypeScript Plugin

### 5.1 Plugin Manifest

`plugin/openclaw.plugin.json`:
```json
{
  "name": "casehub-openclaw",
  "version": "0.2.0",
  "description": "Injects recent Qhorus channel activity as appendSystemContext before each OpenClaw agent turn",
  "entry": "dist/index.js"
}
```

The TypeScript plugin and Python client have independent version series.
`pyproject.toml` uses `0.2.0.dev0`; the plugin starts at `0.2.0`.

### 5.2 TypeScript Interfaces (`src/types.ts`)

No public `@openclaw/plugin-sdk` types package exists. All interfaces are defined locally
and are structurally typed — replace with upstream types if OpenClaw publishes a package.

```typescript
// ChannelContextWindow REST response types — mirror Java WindowContent / ContextMessage records

export interface ContextMessage {
  windowSeq: number;
  channelId: string;
  channelName: string | null;
  messageType: string;          // Qhorus MessageType name: "EVENT", "COMMAND", "STATUS", etc.
  senderId: string;
  correlationId: string | null;
  content: string;
  receivedAt: string;           // ISO-8601
}

export interface WindowContent {
  messages: ContextMessage[];
  lastEvictionWindowSeq: number; // -1 if no eviction
  lastWindowSeq: number;
  currentWindowSeq: number;
  agentHasAssociation: boolean;
  lastChannelActivity: string;   // ISO-8601; epoch sentinel = "1970-01-01T00:00:00Z"
}

// OpenClaw Plugin SDK interfaces — structurally assumed from documented plugin examples.
// The field name `config` on OpenClawPluginApi is provisional; if OpenClaw publishes
// a types package, verify and replace.

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
  on(
    event: "before_prompt_build",
    handler: (ctx: PluginHookContext) => Promise<HookResult>,
  ): void;
  config?: PluginConfig; // provisional — mark with a comment in implementation
}
```

### 5.3 Plugin Class (`src/index.ts`)

```typescript
export class ChannelContextPlugin {
  private readonly client: ChannelClient;

  // Cursor keyed by agentId only (not agentId:sessionKey).
  // Stores the current cursor alongside the sessionKey that produced it.
  // When sessionKey changes (daily session reset or idle timeout), cursor resets to 0,
  // giving the new session a fresh full window from the buffer.
  // Map is bounded by number of distinct agents — not by number of sessions.
  private readonly cursors = new Map<string, { cursor: number; sessionKey: string }>();

  constructor(baseUrl: string, timeoutMs: number) {
    this.client = new ChannelClient(baseUrl, timeoutMs);
  }

  // Synchronous — OpenClaw snapshots hooks at plugin registration time.
  // Registering from start() (async, post-gateway) silently misses the window.
  register(api: OpenClawPluginApi): void {
    api.on("before_prompt_build", (ctx) => this._inject(ctx));
  }

  private async _inject(ctx: PluginHookContext): Promise<HookResult> {
    const entry = this.cursors.get(ctx.agentId);
    // Reset cursor when sessionKey changes — new session gets full window from buffer
    const since = (entry?.sessionKey === ctx.sessionKey) ? (entry?.cursor ?? 0) : 0;

    let result: WindowContent;
    try {
      result = await this.client.getContext(ctx.agentId, since);
    } catch (err) {
      // Fail open — agent turn must never be blocked by context unavailability
      console.warn(`[casehub-openclaw] context fetch failed for ${ctx.agentId}: ${err}`);
      return {};
    }

    // agentHasAssociation=false also covers the case where the service restarted and
    // the agent has not yet re-registered via bindAgent. Once re-registration occurs,
    // agentHasAssociation=true and currentWindowSeq will be low relative to our since,
    // triggering the restart detection below. The ordering is correct but non-obvious.
    if (!result.agentHasAssociation) return {};

    // Service restart: currentWindowSeq reset below our cursor.
    // Skip this turn; next turn will call with since=0 and get a fresh window.
    if (since > result.currentWindowSeq) {
      this.cursors.set(ctx.agentId, { cursor: 0, sessionKey: ctx.sessionKey });
      return {};
    }

    const parts: string[] = [];

    // Overflow notice — additive with messages, not exclusive.
    // Edge case: if eviction occurred but all remaining messages also expired (messages=[]),
    // the overflow notice is still correct (data was lost), but there is no separate idle
    // notice — the empty messages array implicitly signals no current content.
    if (result.lastEvictionWindowSeq > since) {
      parts.push(
        "⚠️ Some channel messages were evicted before this turn (high volume). " +
        "Full history is available in the CaseHub audit ledger.",
      );
    }

    // Available messages — always injected when present
    if (result.messages.length > 0) {
      parts.push(formatMessages(result.messages));
    }

    // Idle notice — only when no messages AND no relevant eviction.
    // When lastEvictionWindowSeq > since, the eviction notice covers the "something happened"
    // signal; injecting an idle notice alongside would be contradictory.
    if (result.messages.length === 0 && result.lastEvictionWindowSeq <= since) {
      parts.push(formatIdle(result.lastChannelActivity));
    }

    // Advance cursor. All non-early-return paths produce at least one part
    // (overflow OR messages OR idle are mutually covering when agentHasAssociation=true
    // and since <= currentWindowSeq), so the parts.length guard below is unreachable
    // in practice but kept for defensive correctness.
    this.cursors.set(ctx.agentId, { cursor: result.lastWindowSeq, sessionKey: ctx.sessionKey });

    if (parts.length === 0) return {};
    return { appendSystemContext: "## Channel Context\n\n" + parts.join("\n\n") };
  }
}

export function register(api: OpenClawPluginApi): void {
  const cfg = api.config ?? {};
  new ChannelContextPlugin(
    cfg.baseUrl ?? "http://localhost:8080",
    cfg.timeoutMs ?? 3000,
  ).register(api);
}
```

### 5.4 Formatters (`src/formatters.ts`)

Extracted to their own module so formatter logic can be unit-tested directly without
going through the full injection path (especially important for the elapsed-time idle
notice, which requires `Date.now()` control).

```typescript
import type { ContextMessage } from "./types.js";

export function formatMessages(messages: ContextMessage[]): string {
  return messages.map((m) => {
    const channel = m.channelName ?? m.channelId;
    const time = new Date(m.receivedAt).toISOString().slice(11, 16) + "Z"; // UTC HH:MMZ
    return `**${m.senderId}** on \`${channel}\` [${m.messageType}] at ${time}:\n${m.content}`;
  }).join("\n\n");
}

export function formatIdle(lastChannelActivity: string): string {
  // Use Date.parse() rather than string comparison — Jackson may serialise Instant.EPOCH
  // as "1970-01-01T00:00:00Z" or "1970-01-01T00:00:00.000Z" depending on version.
  // Date.parse() returns 0 for epoch regardless of millisecond precision.
  const ts = Date.parse(lastChannelActivity);
  if (ts === 0) return "No channel activity recorded for this agent yet.";
  const elapsedMin = Math.floor((Date.now() - ts) / 60_000);
  return `No channel activity in the last ${elapsedMin} minute(s).`;
}
```

### 5.5 HTTP Client (`src/channel-client.ts`)

Uses the global `fetch` (Node.js ≥ 18) with `AbortController` for timeout:

```typescript
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
```

### 5.6 User Configuration

In the OpenClaw user's `openclaw.json`:
```json
{
  "plugins": {
    "load": { "paths": ["./node_modules/casehub-openclaw-plugin"] },
    "entries": {
      "casehub-openclaw": {
        "enabled": true,
        "hooks": { "allowConversationAccess": true },
        "config": {
          "baseUrl": "http://localhost:8080",
          "timeoutMs": 3000
        }
      }
    }
  }
}
```

`hooks.allowConversationAccess: true` is **mandatory** since OpenClaw 2026.4.23.
Without it, the `before_prompt_build` handler is silently dropped — no error, no log.
The README must lead with this requirement.

### 5.7 Tests

Test framework: Vitest. HTTP: `vi.stubGlobal("fetch", mockFetch)`.

**`tests/index.test.ts`** — ten cases:

1. `agentHasAssociation=false` → returns `{}`
2. Service restart (`since=10 > currentWindowSeq=3`) → resets cursor to 0, returns `{}`
3. Messages present → `appendSystemContext` contains formatted messages
4. Overflow + messages (additive) → overflow notice and messages both present
5. Overflow + empty messages → overflow notice only; no idle notice
6. Idle (empty messages, no eviction, real `lastChannelActivity`) → elapsed idle notice
   (use `vi.setSystemTime(fixedNow)` before the test; restore after)
7. Idle (empty messages, no eviction, `lastChannelActivity=epoch`) → "no activity recorded" notice
8. HTTP error (non-2xx) → returns `{}` (fail open, no throw)
9. Timeout (`AbortError`) → returns `{}` (fail open, no throw)
10. Same agent, different `sessionKey` (session reset) → cursor resets to 0; next fetch
    uses `since=0`

**`tests/channel-client.test.ts`** — five cases:

1. Successful response → parsed `WindowContent`
2. HTTP 503 → throws `Error`
3. Timeout → throws `AbortError`
4. `since` query param forwarded correctly
5. `agentId` URL-encoded in path (e.g. `agent/with/slash` → `agent%2Fwith%2Fslash`)

**`tests/formatters.test.ts`** — seven cases:

1. `formatMessages` single message → correct format string (UTC time, channel, speech act)
2. `formatMessages` multiple messages → joined with `\n\n`
3. `formatMessages` null `channelName` → falls back to `channelId`
4. `formatIdle` with epoch `"1970-01-01T00:00:00Z"` → "no activity recorded" string
5. `formatIdle` with epoch `"1970-01-01T00:00:00.000Z"` (millisecond precision) → same string
6. `formatIdle` with real timestamp → matches `/No channel activity in the last \d+ minute\(s\)\./`
   (use `vi.setSystemTime()` for determinism)
7. `formatMessages` `receivedAt` → time rendered as UTC `HH:MMZ`

### 5.8 Build Configuration

**`plugin/package.json`**:
```json
{
  "name": "casehub-openclaw-plugin",
  "version": "0.2.0",
  "type": "module",
  "description": "CaseHub ChannelContextWindow plugin for OpenClaw",
  "main": "dist/index.js",
  "exports": { ".": "./dist/index.js" },
  "engines": { "node": ">=18" },
  "scripts": {
    "build": "tsc",
    "test": "vitest run",
    "test:watch": "vitest"
  },
  "devDependencies": {
    "typescript": "^5.4",
    "vitest": "^1.6",
    "@types/node": "^20"
  }
}
```

Node ≥ 18 required for the global `fetch` API (no polyfill needed above 18).

**`plugin/tsconfig.json`**:
```json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "NodeNext",
    "moduleResolution": "NodeNext",
    "strict": true,
    "outDir": "dist",
    "rootDir": "src",
    "sourceMap": true,
    "declaration": true
  },
  "include": ["src"],
  "exclude": ["tests", "dist"]
}
```

---

## 6. Python Package

### 6.1 Models (`python/src/casehub_openclaw/models.py`)

Pydantic v2 models mirroring the Java records. JSON from the endpoint is camelCase
(default Jackson); Python models use snake_case with field aliases.

```python
from __future__ import annotations
from datetime import datetime
from uuid import UUID
from pydantic import BaseModel, ConfigDict, Field

class ContextMessage(BaseModel):
    model_config = ConfigDict(populate_by_name=True)
    window_seq: int            = Field(alias="windowSeq")
    channel_id: UUID           = Field(alias="channelId")
    channel_name: str | None   = Field(alias="channelName")
    message_type: str          = Field(alias="messageType")
    sender_id: str             = Field(alias="senderId")
    correlation_id: str | None = Field(alias="correlationId")
    content: str
    received_at: datetime      = Field(alias="receivedAt")

class WindowContent(BaseModel):
    model_config = ConfigDict(populate_by_name=True)
    messages: list[ContextMessage]
    last_eviction_window_seq: int   = Field(alias="lastEvictionWindowSeq")
    last_window_seq: int            = Field(alias="lastWindowSeq")
    current_window_seq: int         = Field(alias="currentWindowSeq")
    agent_has_association: bool     = Field(alias="agentHasAssociation")
    last_channel_activity: datetime = Field(alias="lastChannelActivity")
```

`last_channel_activity` parses `"1970-01-01T00:00:00Z"` as a `datetime`. Callers
compare against `datetime(1970, 1, 1, tzinfo=timezone.utc)` to detect "no activity ever".

### 6.2 Client (`python/src/casehub_openclaw/channel_client.py`)

Synchronous. Python skill scripts are synchronous executables; no async context.

```python
from urllib.parse import quote
import httpx
from .models import WindowContent

class ChannelClient:
    """HTTP client for the ChannelContextWindow REST endpoint.

    Uses top-level httpx.get() (one connection per call). For skill scripts
    that call get_context once per execution this is fine. For scripts making
    repeated calls in a loop, construct an httpx.Client explicitly and pass it
    to multiple httpx.get() calls, or use httpx.Client as a context manager.
    """

    def __init__(self, base_url: str, timeout: float = 5.0) -> None:
        self._base_url = base_url.rstrip("/")
        self._timeout = timeout

    def get_context(self, agent_id: str, since: int = 0) -> WindowContent:
        url = f"{self._base_url}/channel-context/{quote(agent_id, safe='')}"
        resp = httpx.get(url, params={"since": since}, timeout=self._timeout)
        resp.raise_for_status()
        return WindowContent.model_validate(resp.json())
```

Raises `httpx.HTTPStatusError` on non-2xx. Raises `httpx.TimeoutException` on timeout.
The client does not fail open — callers decide error handling for their own context.

### 6.3 Public API (`python/src/casehub_openclaw/__init__.py`)

```python
from .channel_client import ChannelClient
from .models import ContextMessage, WindowContent

__all__ = ["ChannelClient", "ContextMessage", "WindowContent"]
```

### 6.4 `pyproject.toml` Changes

Remove from `[project.optional-dependencies].dev`:
- `pytest-asyncio>=0.23` (no async code)
- `httpx[http2]>=0.27` (not needed for sync testing)

Add to `dev`:
- `respx>=0.21` (httpx-native HTTP mocking)
- `build>=1.2` (for `python -m build` packaging)

### 6.5 Files Deleted

- `python/src/casehub_openclaw/context_hook.py` — hook registration logic moves to TypeScript

### 6.6 Tests (`python/tests/test_channel_client.py`)

Nine cases (pytest + respx):

1. Successful response → correct `WindowContent`, all fields deserialized
2. camelCase aliases → snake_case properties map correctly
3. `null` `correlationId` and `channelName` → `None`
4. `lastChannelActivity = "1970-01-01T00:00:00Z"` → `datetime(1970, 1, 1, tzinfo=UTC)`
5. Empty `messages` list → valid model, `messages == []`
6. `lastEvictionWindowSeq = -1` → deserializes as `-1`
7. HTTP 503 → raises `httpx.HTTPStatusError`
8. Timeout → raises `httpx.TimeoutException`
9. `agent_id` containing `/` → URL correctly encodes to `%2F` in path

---

## 7. Failure Modes

| Failure | Detection | Response |
|---|---|---|
| Cache service down | `fetch` throws | Fail open: return `{}`, log warning |
| Timeout | `AbortController` fires | Fail open: return `{}`, log warning |
| HTTP non-2xx | `res.ok === false` | Fail open: return `{}`, log warning |
| Service restart | `since > currentWindowSeq` | Reset cursor to 0, return `{}` this turn; next turn fetches fresh |
| Agent not wired / restarting | `agentHasAssociation=false` | Return `{}` silently; restart recovery happens on re-registration |
| Ring buffer overflow | `lastEvictionWindowSeq > since` | Inject overflow notice (additive with messages) |
| Agent dormant beyond TTL (no eviction) | `messages=[]`, eviction ≤ since | Inject idle notice with elapsed time since last activity |
| Overflow + all remaining messages TTL-expired | `lastEvictionWindowSeq > since`, `messages=[]` | Overflow notice injected; empty messages array signals no current content; no idle notice (contradictory with overflow signal) |
| Multiple OpenClaw instances | Per-instance cursor Map | Redundant context re-delivery on session key collision (not missing context) |

All TypeScript plugin failures result in `{}` return — agent turn continues.
Python client propagates exceptions — callers decide.

---

## 8. Distribution

| Artifact | Registry | Name | Built by |
|---|---|---|---|
| TypeScript plugin | npm | `casehub-openclaw-plugin` | `npm run build` in `plugin/` |
| Python client | PyPI | `casehub-openclaw` | `python -m build` in `python/` |
| Java app | GitHub Packages | `io.casehub.openclaw:*` | `mvn install` |

Publishing is manual for this epic. CI automation is deferred.
Maven does not reference `plugin/` or `python/` (protocol PP-20260525-724406).

---

## 9. Protocol Notes

**New protocol to capture after implementation:**
OpenClaw `before_prompt_build` hooks must be TypeScript plugins. The Python SDK
(`openclaw_sdk`) is an external REST-API wrapper with no hook registration mechanism.
Any plugin requiring `before_prompt_build` must ship a TypeScript entry point registered
via `api.on()` in a synchronous `register(api)` function. Hooks are snapshotted at
registration time — wiring from async `start()` is silently ignored.
