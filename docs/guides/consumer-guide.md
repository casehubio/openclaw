# casehub-openclaw -- Consumer Guide

> Integration tier bridging CaseHub and OpenClaw -- provisions OpenClaw agents as CaseHub workers and provides cross-channel LLM context injection.

**GitHub:** [casehubio/openclaw](https://github.com/casehubio/openclaw)
**Tier:** Integration

---

## Purpose

casehub-openclaw bridges CaseHub and OpenClaw. It provisions OpenClaw instances as CaseHub workers via `/hooks/agent`, provides `ChannelContextWindow` for cross-channel LLM context injection, implements the `ChannelBackend` SPI for bidirectional Qhorus-OpenClaw wiring, and ships a Python SDK component for the `before_prompt_build` hook.

---

## Module Structure

| Module | Contents |
|--------|----------|
| `core` | `ContextMessage`, `WindowContent`, `ChannelRingBuffer`, `ChannelContextWindowService`, `OpenClawHookClient`, REST context endpoint. `OpenClawHookClient.invoke()` has a 5-arg overload accepting explicit `deliveryUrl` (used by `OversightGateService`). `invokeDirect()` is a sessionless overload for DirectCallBridge (openclaw#49). |
| `casehub` | `ChannelContextWindowObserver` (`MessageObserver` SPI), `OpenClawChannelBackend` (`ChannelBackend` SPI), `OpenClawWorkerProvisioner`, `OpenClawCaseChannelProvider`, `OpenClawWorkerStatusListener`, `OpenClawAgentRegistry`. DirectCallBridge classes: `DirectCallBridge`, `OpenClawAgentProvider` (`AgentProvider` SPI), `OpenClawChatModel` (langchain4j `ChatModel` bridge). `OversightGateService` for oversight gate lifecycle. |
| `app` | Runnable Quarkus application wiring core + casehub modules. REST endpoints for delivery webhooks, direct-call bridge, channel context, MCP tools, plugin API, and demo scenarios. |
| `plugin` | TypeScript OpenClaw plugin -- `before_prompt_build` hook via Plugin SDK; published to npm. TypeScript-only due to OpenClaw Plugin SDK design (see ADR 0001). |
| `python` | Python channel client library (thin HTTP wrapper); published to PyPI. No hook registration (hooks are TypeScript-only). |

---

## Hook API

| Endpoint | Direction | Purpose |
|----------|-----------|---------|
| `POST /hooks/agent` | CaseHub -> OpenClaw | Deliver a case step prompt to a running OpenClaw agent |
| `POST /hooks/wake` | CaseHub -> OpenClaw | Wake a dormant agent with context |
| `deliver:webhook` | OpenClaw -> CaseHub | Heartbeat or result delivery from an autonomous agent |
| `POST /openclaw/direct-call/{correlationId}` | OpenClaw -> CaseHub | DirectCallBridge response delivery -- completes the caller's `CompletableFuture` (openclaw#49) |

---

## Two Invocation Modes

**Heartbeat (OpenClaw autonomous -> CaseHub):** An OpenClaw agent running autonomously produces output and delivers it via `deliver:webhook`. The integration layer normalises the payload and creates a CaseHub case to track the work.

**Direct call (CaseHub case step -> OpenClaw):** A running CaseHub case reaches a step that routes to an OpenClaw agent. The integration layer calls `POST /hooks/agent` with the step context as the agent prompt.

These two modes are mutually exclusive per invocation. A given agent interaction is either initiated by OpenClaw or by CaseHub -- never both simultaneously. **This is the golden rule for reasoning about invocation flow.**

---

## ChannelContextWindow

`MessageObserver` implementation (`ChannelContextWindowObserver`) that maintains an in-memory ring buffer of recent cross-channel messages. In-memory only, best-effort -- no JPA, no Flyway, no named datasource. The correctness layer is Qhorus (ledger); `ChannelContextWindow` is the intelligence layer only.

Exposed as `GET /channel-context/{agentId}?since={seq}` -- the Python SDK calls this before prompt construction to inject relevant channel history into the system context.

---

## MCP Tools and Resources (Layer 0)

**Transport:** `POST /mcp` -- streamable-HTTP via `quarkus-mcp-server`.

**Tools:**

| Tool | Purpose |
|------|---------|
| `casehub_commit` | Declare a commitment for a channel |
| `casehub_done` | Mark a commitment fulfilled |
| `casehub_reject` | Decline/reject a commitment |
| `casehub_checkpoint` | Record a progress checkpoint |
| `casehub_escalate` | Escalate to oversight channel |
| `casehub_create_workitem` | Create a human WorkItem |
| `casehub_open_case` | Open a new case instance |
| `casehub_status` | Query agent commitment status |
| `casehub_queue` | Queue a task for deferred execution |

**Resources:**

| URI | What it exposes |
|-----|----------------|
| `casehub://agent/{id}/commitments` | Active commitments for a given agent |
| `casehub://agent/{id}/cases` | Open cases for a given agent |
| `casehub://channel/{id}/recent` | Recent messages in a channel |

---

## Plugin SDK

TypeScript `before_prompt_build` hook implemented via OpenClaw Plugin SDK in `plugin/`. Calls `GET /channel-context/{agentId}` and invokes `appendSystemContext` to prepend CaseHub channel history into the agent's system prompt before each LLM call. Published to npm. ADR 0001 documents the TypeScript-only decision.

**Plugin hooks (TypeScript):**

| Hook | Role |
|------|------|
| `before_tool_call` | Pre-flight commitment check before tool execution |
| `agent_end` | Flush pending commitments on session close |
| `session_start` | Bootstrap session context and active commitments |
| `heartbeat_prompt_contribution` | Inject commitment state into recurring heartbeat prompts |

The `python/` library is a thin HTTP client (no hook registration); published to PyPI independently.

---

## Dependencies

- `casehub-qhorus` -- mandatory (`ChannelBackend` SPI, `MessageObserver` SPI)
- `casehub-engine-api` -- SPI interfaces only (`WorkerProvisioner`, `CaseChannelProvider`, `WorkerStatusListener`). Uses `casehub-engine-api` rather than `casehub-engine` to avoid pulling engine CDI beans with unsatisfied persistence SPIs into the `casehub/` module.
- `casehub-platform-api` -- `CurrentPrincipal`, `GroupMembershipProvider` for permission-aware context injection
- `casehub-platform-agent-api` -- `AgentProvider` SPI, `AgentSessionConfig`, `AgentEvent` for DirectCallBridge (openclaw#49)
- `langchain4j-core` -- `ChatModel` interface for `OpenClawChatModel` bridge (openclaw#49)

---

## What This Repo Does NOT Do

- Replace Claudony -- different worker types (Claude CLI vs OpenClaw agents); both are valid `WorkerProvisioner` implementations
- Implement OpenClaw's skill engine -- executes skills via `/hooks/agent` prompt routing; skill authoring and packaging is OpenClaw's concern
- Own Qhorus channel semantics or the commitment lifecycle -- those belong to casehub-qhorus
- Own case orchestration or `CasePlanModel` -- that is casehub-engine
