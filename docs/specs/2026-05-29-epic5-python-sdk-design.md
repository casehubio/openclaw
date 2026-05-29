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
│       └── channel-client.ts      fetch-based HTTP client
│   └── tests/
│       ├── index.test.ts          hook logic: all injection cases
│       └── channel-client.test.ts HTTP client
│
├── python/                        Python — PyPI: casehub-openclaw
│   ├── pyproject.toml             (trimmed: remove pytest-asyncio; add respx)
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

Maven does not reference `plugin/` or `python/`. Independent CI publish steps required
for npm and PyPI (not covered in this epic — publishing is manual for now).

---

## 4. REST API Contract (Source of Truth)

`GET /channel-context/{agentId}?since={windowSeq}`

Always returns 200. An unknown `agentId` returns `noAssociation()` (not 404).
`since` defaults to 0 when omitted.

Response body (Java `WindowContent` record serialized as camelCase JSON):

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
- `lastEvictionWindowSeq` — `-1` if no eviction has occurred; otherwise the `windowSeq`
  of the most recently evicted message across all associated channels
- `lastWindowSeq` — max `windowSeq` of returned messages; equals `since` if none returned
  (cursor only advances when new messages arrive)
- `currentWindowSeq` — service's `AtomicLong` at query time; used for restart detection
- `agentHasAssociation` — `false` if the agentId is unknown to the service → caller skips injection
- `lastChannelActivity` — `"1970-01-01T00:00:00Z"` (`Instant.EPOCH`) if no messages ever received

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

### 5.2 OpenClaw Type Interfaces

No public `@openclaw/plugin-sdk` types package exists. Define locally in `src/types.ts`:

```typescript
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
  config?: unknown;
}

export interface PluginConfig {
  baseUrl?: string;
  timeoutMs?: number;
}
```

Structurally typed — replace with upstream types if OpenClaw publishes a types package.

### 5.3 Plugin Class

`plugin/src/index.ts`:

```typescript
export class ChannelContextPlugin {
  private readonly client: ChannelClient;
  // Cursor per agentId:sessionKey — independent per session
  private readonly cursors = new Map<string, number>();

  constructor(baseUrl: string, timeoutMs: number) {
    this.client = new ChannelClient(baseUrl, timeoutMs);
  }

  // Synchronous — OpenClaw snapshots hooks at registration time.
  // Registering from start() silently misses the window.
  register(api: OpenClawPluginApi): void {
    api.on("before_prompt_build", (ctx) => this._inject(ctx));
  }

  private async _inject(ctx: PluginHookContext): Promise<HookResult> {
    const cursorKey = `${ctx.agentId}:${ctx.sessionKey}`;
    const since = this.cursors.get(cursorKey) ?? 0;

    let result: WindowContent;
    try {
      result = await this.client.getContext(ctx.agentId, since);
    } catch (err) {
      // Fail open — agent turn must not be blocked by context unavailability
      console.warn(`[casehub-openclaw] context fetch failed for ${ctx.agentId}: ${err}`);
      return {};
    }

    // Agent not yet wired to any Qhorus channels — skip silently
    if (!result.agentHasAssociation) return {};

    // Service restart: currentWindowSeq reset below our cursor
    // Skip this turn; next turn will call with since=0 and get a fresh window
    if (since > result.currentWindowSeq) {
      this.cursors.set(cursorKey, 0);
      return {};
    }

    const parts: string[] = [];

    // Overflow notice — additive with messages, not exclusive
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

    // Idle notice — only when no messages AND no relevant eviction
    if (result.messages.length === 0 && result.lastEvictionWindowSeq <= since) {
      parts.push(formatIdle(result.lastChannelActivity));
    }

    // Advance cursor — only moves forward when new messages arrive
    this.cursors.set(cursorKey, result.lastWindowSeq);

    if (parts.length === 0) return {};
    return { appendSystemContext: "## Channel Context\n\n" + parts.join("\n\n") };
  }
}

export function register(api: OpenClawPluginApi): void {
  const cfg = (api.config ?? {}) as PluginConfig;
  new ChannelContextPlugin(
    cfg.baseUrl ?? "http://localhost:8080",
    cfg.timeoutMs ?? 3000,
  ).register(api);
}
```

### 5.4 Formatting Helpers

`formatMessages(messages: ContextMessage[]): string` — formats each message as:
```
**{senderId}** on `{channelName ?? channelId}` [{messageType}] at {HH:MM}:
{content}
```
Messages joined with `\n\n`.

`formatIdle(lastChannelActivity: string): string`:
- `lastChannelActivity === "1970-01-01T00:00:00Z"` → `"No channel activity recorded for this agent yet."`
- Otherwise → `"No channel activity in the last {N} minute(s)."` (elapsed from `Date.now()`)

### 5.5 HTTP Client

`plugin/src/channel-client.ts` — uses `fetch` with `AbortController` for timeout:

```typescript
export class ChannelClient {
  constructor(private readonly baseUrl: string, private readonly timeoutMs: number) {}

  async getContext(agentId: string, since: number): Promise<WindowContent> {
    const url = `${this.baseUrl}/channel-context/${encodeURIComponent(agentId)}?since=${since}`;
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
The README must lead with this.

### 5.7 Tests

Test framework: Vitest. HTTP: `vi.stubGlobal("fetch", mockFetch)`.

Ten test cases in `tests/index.test.ts`:
1. `agentHasAssociation=false` → returns `{}`
2. Service restart (`since=10 > currentWindowSeq=3`) → resets cursor to 0, returns `{}`
3. Messages present → `appendSystemContext` contains formatted messages
4. Overflow + messages (additive) → overflow notice and messages both present
5. Overflow + empty messages → overflow notice only; no idle notice
6. Idle (empty messages, no eviction, real `lastChannelActivity`) → elapsed idle notice
7. Idle (empty messages, no eviction, `lastChannelActivity=epoch`) → "no activity recorded" notice
8. HTTP error (non-2xx) → returns `{}` (fail open, no throw)
9. Timeout (AbortError) → returns `{}` (fail open, no throw)
10. Two sessions same agent → cursors are independent; each advances separately

Five test cases in `tests/channel-client.test.ts`:
1. Successful response → parsed `WindowContent`
2. HTTP 503 → throws `Error`
3. Timeout → throws `AbortError`
4. `since` query param forwarded correctly
5. `agentId` URL-encoded in path

---

## 6. Python Package

### 6.1 Models

`python/src/casehub_openclaw/models.py` — Pydantic v2, camelCase aliases:

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

`last_channel_activity` parses `"1970-01-01T00:00:00Z"` as a `datetime`. Callers compare
against `datetime(1970, 1, 1, tzinfo=timezone.utc)` to detect "no activity ever".

### 6.2 Client

`python/src/casehub_openclaw/channel_client.py`:

```python
import httpx
from .models import WindowContent

class ChannelClient:
    def __init__(self, base_url: str, timeout: float = 5.0) -> None:
        self._base_url = base_url.rstrip("/")
        self._timeout = timeout

    def get_context(self, agent_id: str, since: int = 0) -> WindowContent:
        url = f"{self._base_url}/channel-context/{agent_id}"
        resp = httpx.get(url, params={"since": since}, timeout=self._timeout)
        resp.raise_for_status()
        return WindowContent.model_validate(resp.json())
```

Raises `httpx.HTTPStatusError` on non-2xx. Raises `httpx.TimeoutException` on timeout.
The client does not fail open — callers decide error handling for their context.

### 6.3 Public API

`python/src/casehub_openclaw/__init__.py`:
```python
from .channel_client import ChannelClient
from .models import ContextMessage, WindowContent

__all__ = ["ChannelClient", "ContextMessage", "WindowContent"]
```

### 6.4 pyproject.toml Changes

Remove from `[project.optional-dependencies].dev`:
- `pytest-asyncio>=0.23` (no async code)
- `httpx[http2]>=0.27` (not needed for sync testing)

Add to dev:
- `respx>=0.21` (httpx-native HTTP mocking)

### 6.5 Files Deleted

- `python/src/casehub_openclaw/context_hook.py` — hook registration logic moves to TypeScript

### 6.6 Tests

`python/tests/test_channel_client.py` — pytest + respx:

Eight cases:
1. Successful response → correct `WindowContent`, all fields deserialized
2. camelCase aliases → snake_case properties map correctly
3. `null` `correlationId` and `channelName` → `None`
4. `lastChannelActivity = "1970-01-01T00:00:00Z"` → `datetime(1970, 1, 1, tzinfo=UTC)`
5. Empty `messages` list → valid model
6. `lastEvictionWindowSeq = -1` → deserializes as `-1`
7. HTTP 503 → raises `httpx.HTTPStatusError`
8. Timeout → raises `httpx.TimeoutException`

---

## 7. Failure Modes

| Failure | Detection | Response |
|---|---|---|
| Cache service down | `fetch` throws | Fail open: return `{}`, log warning |
| Timeout | `AbortController` fires | Fail open: return `{}`, log warning |
| HTTP non-2xx | `res.ok === false` | Fail open: return `{}`, log warning |
| Service restart | `since > currentWindowSeq` | Reset cursor to 0, return `{}` this turn |
| Agent not wired | `agentHasAssociation=false` | Return `{}` silently |
| Ring buffer overflow | `lastEvictionWindowSeq > since` | Inject overflow notice (additive) |
| Agent dormant beyond TTL | `messages=[]`, no eviction | Inject idle notice with elapsed time |
| Multiple OpenClaw instances | Per-instance cursor Map | Redundant context re-delivery (not missing context) |

All failures in the TypeScript plugin result in `{}` return (agent turn continues).
The Python client propagates exceptions — callers decide.

---

## 8. Distribution

| Artifact | Registry | Name | Built by |
|---|---|---|---|
| TypeScript plugin | npm | `casehub-openclaw-plugin` | `npm run build` in `plugin/` |
| Python client | PyPI | `casehub-openclaw` | `pip build` in `python/` |
| Java app | GitHub Packages | `io.casehub.openclaw:*` | Maven |

Publishing is manual for this epic. CI automation is deferred.
Maven does not reference `plugin/` or `python/` (protocol PP-20260525-724406).

---

## 9. Protocol Notes

**New protocol to capture after implementation:**
OpenClaw `before_prompt_build` hooks must be TypeScript plugins. The Python SDK
(`openclaw_sdk`) is an external REST-API wrapper with no hook registration mechanism.
Any plugin requiring `before_prompt_build` must ship a TypeScript entry point registered
via `api.on()` in a synchronous `register(api)` function. Hooks snapshotted at
registration time — async `start()` is too late.
