# casehub-openclaw -- Consumer Guide

> Integration tier bridging CaseHub and OpenClaw -- provisions OpenClaw agents as CaseHub workers, provides cross-channel LLM context injection, and exposes CaseHub commitment lifecycle via MCP tools.

**GitHub:** [casehubio/openclaw](https://github.com/casehubio/openclaw)
**Tier:** Integration

---

## Purpose

casehub-openclaw bridges the CaseHub case management platform and the OpenClaw agent execution platform. It provisions OpenClaw agents as CaseHub workers via `WorkerProvisioner`, provides `ChannelContextWindow` for cross-channel LLM context injection into agent system prompts, implements the `ChannelBackend` SPI for bidirectional Qhorus-OpenClaw message routing, exposes CaseHub commitment lifecycle via MCP tools for in-turn agent use, and ships a TypeScript plugin SDK and Python client library for OpenClaw-side integration.

---

## Module Structure

Three Maven modules plus two non-Maven packages:

| Module | Artifact | Contents |
|--------|----------|----------|
| `core` | `casehub-openclaw-core` | `OpenClawHookClient` (session registry, `invoke()`, `invokeDirect()`, `wake()`), `ChannelContextWindowService` (two-phase agent/channel binding, ring buffer management, TTL eviction), `ChannelRingBuffer`, `ContextMessage`, `WindowContent`, `OpenClawGatewayClient` (MicroProfile REST Client interface), `AgentInvocationRequest`, `AgentWakeRequest`, `OpenClawClientConfig`, `OpenClawSession`. |
| `casehub` | `casehub-openclaw-casehub` | CaseHub SPI implementations: `OpenClawWorkerProvisioner` (`WorkerProvisioner`), `OpenClawChannelBackend` (`ChannelBackend`), `OpenClawCaseChannelProvider` (`CaseChannelProvider`), `OpenClawWorkerStatusListener` (`WorkerStatusListener`), `ChannelContextWindowObserver` (`MessageObserver`), `OpenClawAgentProvider` (`AgentProvider` for DirectCallBridge), `OpenClawChatModel` (langchain4j `ChatModel` bridge). `DirectCallBridge` (synchronous webhook bridge via `CompletableFuture`), `OversightGateService` (gate lifecycle with `ActionRiskClassifier` CDI integration), `OversightGateDispatcher`, `OpenClawAgentRegistry` (1:N agents per case), `OpenClawAgentConfigResolver` (`ProvisionerConfigRegistry` adapter), `GateContext`, `CaseChannelNames`. Scenario subsystem: `ScenarioDef`, `ScenarioStateStore`, `ScenarioObserver` (`MessageObserver`), `ScenarioMetadataProvider`, `ScenarioEventListener`, `CaseExecutionEvent` hierarchy. |
| `app` | `casehub-openclaw-app` | Runnable Quarkus application wiring core + casehub modules. REST endpoints: delivery webhooks (`OpenClawDeliveryResource`, `OpenClawOversightDeliveryResource`), direct-call delivery (`DirectCallDeliveryResource`), channel context (`ChannelContextWindowResource`), plugin auto-commit (`PluginCommitResource`). MCP tools (`CommitmentTools`, `QueryTools`, `WorkitemTools`) and resources (`CasehubMcpResources`). Demo scenario REST + SSE (`ScenarioRestResource`, `ScenarioSseResource`, `ScenarioExecutionService`). Security: `PluginTokenBridgeMechanism` (bearer token auth for plugin paths), `DeliveryTokenValidator`. `EvictionScheduler` for TTL-based ring buffer cleanup. Demo UI via Quinoa (Lit Web Components). |
| `plugin/` | npm: `casehub-openclaw-plugin` | TypeScript OpenClaw plugin -- `before_prompt_build` hook (context injection), `before_tool_call` / `agent_end` / `session_start` hooks (commitment lifecycle). Published to npm. TypeScript-only due to OpenClaw Plugin SDK design (ADR-0001). |
| `python/` | PyPI: `casehub-openclaw` | Python channel client library -- `ChannelClient` (thin HTTP wrapper over `httpx`), `ContextMessage` and `WindowContent` Pydantic models. No hook registration (hooks are TypeScript-only). Published to PyPI. |

**Non-module directories:**

| Directory | Contents |
|-----------|----------|
| `examples/` | Three reference scenarios with agent system prompts, mock services, and SKILL.md files: `multi-agent-dev-team`, `trading-oversight`, `incident-response`. |
| `skills/` | OpenClaw SKILL.md files for MCP tools: `casehub-block`, `casehub-case`, `casehub-delegate`, `casehub-global`, `casehub-queue`, `casehub-reject`, `casehub-status`, `casehub-workitem`. |

---

## REST Endpoints

| Endpoint | Direction | Auth | Purpose |
|----------|-----------|------|---------|
| `POST /openclaw/delivery/channel/{channelId}` | OpenClaw -> CaseHub | Delivery token (query param) | Receive agent webhook output; archive as non-resolving STATUS via `OversightGateService.evaluate()` |
| `POST /openclaw/delivery/oversight/{gateId}` | OpenClaw -> CaseHub | Delivery token (query param) | Receive human oversight response; process via `OversightGateService.fulfill()` |
| `POST /openclaw/direct-call/{correlationId}` | OpenClaw -> CaseHub | Delivery token (query param) | DirectCallBridge response delivery -- completes the caller's `CompletableFuture` |
| `GET /channel-context/{agentId}?since={seq}` | Plugin -> CaseHub | Bearer token (`PluginTokenBridgeMechanism`) | Query ChannelContextWindow for recent cross-channel messages |
| `POST /openclaw/plugin/commit` | Plugin -> CaseHub | Bearer token (`PluginTokenBridgeMechanism`) | Auto-commit: open a self-commit for an agent turn |
| `POST /openclaw/plugin/done` | Plugin -> CaseHub | Bearer token (`PluginTokenBridgeMechanism`) | Auto-commit: close an auto-committed commitment |
| `GET /openclaw/plugin/commitments/{agentId}` | Plugin -> CaseHub | Bearer token (`PluginTokenBridgeMechanism`) | List open commitments for `session_start` injection |
| `POST /mcp` | MCP client -> CaseHub | OIDC / HTTP security policy | MCP streamable-HTTP transport -- tool and resource access for agents |
| `GET /api/scenarios` | UI -> CaseHub | PermitAll | List demo scenario summaries |
| `GET /api/scenarios/{id}/state` | UI -> CaseHub | PermitAll | Current scenario state snapshot |
| `POST /api/scenarios/{id}/start` | UI -> CaseHub | RolesAllowed(ADMIN) | Start a demo scenario execution |
| `PUT /api/scenarios/{id}/workitems/{gateId}/complete` | UI -> CaseHub | RolesAllowed(ADMIN) | Approve or reject an oversight gate in a demo scenario |

---

## Two Invocation Modes

**Webhook delivery (OpenClaw autonomous -> CaseHub):** An OpenClaw agent running autonomously produces output and delivers it to `POST /openclaw/delivery/channel/{channelId}`. The delivery resource archives the output as a non-resolving STATUS message on the work channel. Completion signaling is exclusively via MCP tool calls (`casehub_done`, `casehub_reject`, etc.) -- not text classification (ADR-0004).

**Direct call (CaseHub case step -> OpenClaw):** A running CaseHub case reaches a step that routes to an OpenClaw agent. The `OpenClawChannelBackend` calls `OpenClawHookClient.invoke()` with the step content as the agent prompt. The COMMAND message includes a fully-resolved commitment context block with the `commitmentId` and available MCP tool signatures.

**Parallel COMMAND routing (openclaw#70):** When a COMMAND carries a `target` field, `OpenClawChannelBackend.post()` routes to the specific named agent rather than picking an arbitrary agent from the case's registered set. This enables multi-agent cases where different agents handle different steps concurrently.

These two modes are mutually exclusive per invocation. A given agent interaction is either initiated by OpenClaw or by CaseHub -- never both simultaneously.

---

## ChannelContextWindow

`ChannelContextWindowObserver` (a `MessageObserver` SPI implementation) feeds every dispatched Qhorus message into an in-memory ring buffer managed by `ChannelContextWindowService`. In-memory only, best-effort -- no JPA, no Flyway, no named datasource. The correctness layer is Qhorus (ledger); `ChannelContextWindow` is the intelligence layer only.

**Two-phase association:** `bindAgent(agentId, caseId)` is called by `OpenClawWorkerProvisioner` at provision time. `bindChannel(caseId, channelId)` is called by `OpenClawCaseChannelProvider` when a channel is opened. The service joins at query time -- no cross-SPI coordination at write time.

**Lifecycle cleanup:** `unbindAgent(agentId)` is called by `OpenClawWorkerStatusListener.onWorkerCompleted()`. `closeCase(caseId)` removes all channel associations and ring buffers for a closed case. Late messages after case close silently no-op.

**TTL eviction:** `EvictionScheduler` (quarkus-scheduler) calls `ChannelContextWindowService.evictExpired()` at the TTL interval (default 30 minutes). Expired entries are never returned to callers regardless of eviction timing -- `ChannelRingBuffer.query()` applies TTL filtering on every call.

**Configuration:**
- `casehub.openclaw.context-window.max-messages-per-channel` (default: 100) -- ring buffer capacity per channel
- `casehub.openclaw.context-window.ttl` (default: PT30M) -- message retention duration

Exposed as `GET /channel-context/{agentId}?since={seq}` -- the TypeScript plugin calls this in `before_prompt_build` to inject relevant channel history into the system context. The Python SDK also calls this endpoint.

---

## MCP Tools and Resources

**Transport:** `POST /mcp` -- streamable-HTTP via `quarkus-mcp-server` (ADR-0002).

### Tools

Defined in three classes in `app/src/main/java/.../mcp/`:

**CommitmentTools** -- commitment lifecycle (7 tools):

| Tool | Purpose |
|------|---------|
| `casehub_commit` | Register a CaseHub commitment and arm a Watchdog. For case steps, `commitmentId` is provided in the COMMAND message -- call `casehub_done` directly. Use this tool only for early STATUS acknowledgment (channel-backed) or self-tracked commitments. |
| `casehub_done` | Close a commitment. Dispatches DONE to the originating channel (channel-backed) or calls `CommitmentService.fulfill()` (self-commit). If the action requires human oversight, returns a pending gate response via `OversightGateService.openGate()`. |
| `casehub_reject` | Decline a commitment -- DECLINE speech act. Reason is required and recorded in the ledger. |
| `casehub_checkpoint` | Report progress -- dispatches STATUS to the originating channel and resets the Watchdog TTL. |
| `casehub_escalate` | Escalate to a human or named agent -- dispatches HANDOFF. The Watchdog continues for the escalation target. |
| `casehub_block` | Temporarily block a commitment when an external dependency prevents progress. Extends the Watchdog deadline. Only the obligor may call this. |
| `casehub_delegate` | Transfer a commitment to a named agent or person -- dispatches HANDOFF. Use for deliberate delegation, not capability escalation. |

**QueryTools** -- read-only queries (1 tool):

| Tool | Purpose |
|------|---------|
| `casehub_status` | Query commitment status by commitmentId. Returns state, obligor, and deadline. |

**WorkitemTools** -- work item creation and routing (2 tools):

| Tool | Purpose |
|------|---------|
| `casehub_create_workitem` | Create a CaseHub work item with a deadline and Watchdog. Provide either `assignee` or `queueName` (mutually exclusive). Routes to the `work/{name}` channel. |
| `casehub_queue` | Route a task to a named CaseHub queue without specifying an assignee. Whoever monitors the queue picks it up. |

### Resources

Defined in `CasehubMcpResources`:

| URI | What it exposes |
|-----|----------------|
| `casehub://agent/{agentId}/commitments` | Open and acknowledged commitments for a given agent. Terminal states excluded. |
| `casehub://channel/{agentId}/recent` | Recent channel context from the ChannelContextWindow (full window, since=0). |

---

## Plugin SDK (TypeScript)

The TypeScript plugin in `plugin/` implements four OpenClaw hooks. Published to npm as `casehub-openclaw-plugin`.

**Entry point:** `register(api)` in `plugin/src/index.ts`. Configurable via `api.config`:
- `baseUrl` (default: `http://localhost:8080`) -- Quarkus app URL
- `timeoutMs` (default: 3000) -- HTTP request timeout
- `casehub.autoCommit` (default: false) -- enable auto-commit on tool calls
- `casehub.pluginToken` -- bearer token for `PluginTokenBridgeMechanism` auth

**Hooks:**

| Hook | Class | Behaviour |
|------|-------|-----------|
| `before_prompt_build` | `ChannelContextPlugin` | Calls `GET /channel-context/{agentId}?since={cursor}` and invokes `appendSystemContext` to prepend channel history into the system prompt. Cursor-based incremental fetching; cursor resets on session key change. Handles service restart detection (windowSeq regression), eviction notices, and idle notices. Fail-open: context unavailability never blocks the agent turn. |
| `before_tool_call` | `CommitmentManager` | If `autoCommit=true` and the tool is not in the exclusion set (`casehub_status`, `casehub_commit`, `casehub_done`, `casehub_reject`, `casehub_checkpoint`, `casehub_escalate`, `casehub_queue`), opens one commitment per turn via `POST /openclaw/plugin/commit`. On `casehub_escalate`, clears the turn commitment without closing it (Watchdog continues for the escalation target). |
| `agent_end` | `CommitmentManager` | If an auto-committed commitment is open for this turn, closes it via `POST /openclaw/plugin/done`. |
| `session_start` | `CommitmentManager` | Queries `GET /openclaw/plugin/commitments/{agentId}` for orphaned commitments from prior sessions and injects them into the system context via `appendSystemContext`. |

**Plugin endpoints are NOT MCP tools.** The plugin calls Quarkus REST directly (`/openclaw/plugin/*`); MCP is the LLM-facing surface only (ADR-0002).

---

## Python Client Library

The Python library in `python/` is a thin HTTP client for the ChannelContextWindow endpoint. Published to PyPI as `casehub-openclaw`.

```python
from casehub_openclaw import ChannelClient, WindowContent

client = ChannelClient("http://localhost:8080", timeout=5.0)
window: WindowContent = client.get_context("agent-1", since=0)
```

- Uses `httpx` for HTTP, `pydantic` for model validation
- Requires Python >= 3.11
- No hook registration (hooks are TypeScript-only per ADR-0001)
- `ContextMessage` and `WindowContent` Pydantic models mirror the Java records

---

## Agent Configuration

Agent configuration is resolved by `OpenClawAgentConfigResolver`, which merges two sources:

1. **`ProvisionerConfigRegistry`** (from `casehub-ops`) -- deployment-time agent configuration from the config store. Keys: `sessionKey` (required), `capabilities` (list of strings).
2. **Local `OpenClawCasehubConfig`** -- application.properties fallback for development.

`OpenClawWorkerProvisioner.provision()` resolves agents by capability subset match: an agent is a candidate if every requested capability is in its configured set. First alphabetical match wins; multiple matches log a warning.

---

## Agent Registry (1:N Support)

`OpenClawAgentRegistry` supports multiple agents registered for the same case simultaneously (openclaw#63). Four concurrent maps:
- `agentToCase`: agentId -> caseId
- `caseToAgents`: caseId -> Set of agentIds
- `agentToSessionKey`: agentId -> sessionKey
- `caseToTenancy`: caseId -> tenancyId

`findAgentId(caseId)` returns any single agent (legacy path with warning if multiple exist). `findAgentIds(caseId)` returns the full set for parallel routing.

---

## Oversight Gate Lifecycle

`OversightGateService` implements the `io.casehub.api.spi.OversightGateService` SPI:

1. **`evaluate()`** -- called by the delivery webhook for every OpenClaw result. Archives the agent text output as a non-resolving STATUS message on the work channel. No completion signaling occurs here (ADR-0004).

2. **`openGate()`** -- called by `CommitmentTools.done()`. Classifies the proposed action via `ActionRiskClassifier` CDI beans (most-restrictive-wins). If `GateRequired`, dispatches a COMMAND to the oversight channel with serialized gate context; returns `GateOutcome.GatePending`. If `Autonomous` (or no classifiers registered), returns `GateOutcome.Autonomous` so the caller proceeds with normal DONE dispatch. Fail-open on infrastructure errors; fail-safe (GateRequired) on classifier exceptions.

3. **`fulfill()`** -- called by the oversight delivery webhook when a human responds. Parses approval from the first word of the response ("approved" = approved; anything else = rejected). Deserializes gate context from the Qhorus COMMAND message content. Dispatches DONE or DECLINE to close the agent's work commitment.

---

## DirectCallBridge

Request-reply bridge over async webhooks. Enables synchronous `AgentProvider` and langchain4j `ChatModel` invocations without requiring a persistent OpenClaw session.

**Flow:** Caller -> `OpenClawAgentProvider.invoke()` -> `DirectCallBridge.submit(correlationId, timeout)` registers a `CompletableFuture` -> `OpenClawHookClient.invokeDirect()` calls `/hooks/agent` sessionlessly with delivery URL `POST /openclaw/direct-call/{correlationId}` -> OpenClaw processes prompt -> POSTs result to delivery URL -> `DirectCallDeliveryResource` calls `bridge.complete(correlationId, output)` -> future completes -> caller unblocked.

`OpenClawChatModel` wraps `OpenClawAgentProvider` as a langchain4j `ChatModel`: extracts system/user prompts from `ChatRequest`, delegates to `AgentProvider.invoke()`, prepends JSON schema as text preamble when `ResponseFormat` is specified, validates JSON output.

---

## Demo Scenarios

The `examples/` directory contains three reference multi-agent scenarios:

| Scenario | Agents | Description |
|----------|--------|-------------|
| `multi-agent-dev-team` | planner, coder, reviewer | Software development workflow with code review gate |
| `trading-oversight` | signal, execution, risk | Trading with risk oversight gate for high-value trades |
| `incident-response` | investigator, resolver | Incident response with escalation to human on-call |

Each scenario includes agent system prompts, mock service scripts (Python), and a `casehub-example` SKILL.md.

The demo UI (`app/src/main/webui/`) is a Lit Web Components dashboard built with `casehub-pages` and `casehub-blocks-ui`. It provides real-time scenario execution monitoring via SSE (`ScenarioSseResource`). The Quinoa extension bundles the frontend with the Quarkus app.

---

## Dependencies

| Dependency | Usage |
|------------|-------|
| `casehub-qhorus` | `ChannelBackend` SPI, `MessageObserver` SPI, `ChannelService`, `MessageService`, `CommitmentService`, `CommitmentStore`, `ChannelGateway` |
| `casehub-engine-api` | SPI interfaces: `WorkerProvisioner`, `CaseChannelProvider`, `WorkerStatusListener`, `ActionRiskClassifier`, `OversightGateService`, `ProvisionerConfigRegistry`, `NormativeChannelLayout`, `PlannedAction` |
| `casehub-engine-common` | `@CrossTenant` qualifier, `CrossTenantChannelStore`, `CrossTenantMessageStore` |
| `casehub-platform-api` | `CurrentPrincipal`, `GroupMembershipProvider`, `ActorType` |
| `casehub-platform-agent-api` | `AgentProvider` SPI, `AgentSessionConfig`, `AgentEvent`, `AgentSession` |
| `casehub-platform-oidc` | `OidcCurrentPrincipal` `@RequestScoped` (OIDC authentication) |
| `langchain4j-core` | `ChatModel` interface for `OpenClawChatModel` bridge |
| `quarkus-mcp-server-http` | MCP streamable-HTTP transport (`@Tool`, `@ResourceTemplate` annotations) |
| `quarkus-quinoa` | Lit Web Components demo UI bundling |

---

## Architecture Decision Records

| ADR | Title | Status |
|-----|-------|--------|
| 0001 | OpenClaw Hook Implementation Language | Accepted -- TypeScript Plugin SDK (Python SDK has no hook registration) |
| 0002 | MCP Server Host Process | Decided -- Quarkus-embedded (eliminates triple-hop, single process) |
| 0003 | Speech act fallback on unrecognised output | Superseded by ADR-0004 |
| 0004 | Completion signaling via MCP tool calls | Accepted -- tool-call-first; text classification deleted |

---

## What This Repo Does NOT Do

- **Replace Claudony** -- different worker types (Claude CLI vs OpenClaw agents); both are valid `WorkerProvisioner` implementations
- **Implement OpenClaw's skill engine** -- executes skills via `/hooks/agent` prompt routing; skill authoring and packaging is OpenClaw's concern
- **Own Qhorus channel semantics or the commitment lifecycle** -- those belong to casehub-qhorus
- **Own case orchestration or `CasePlanModel`** -- that is casehub-engine
- **Classify agent text output** -- completion signaling is exclusively via MCP tool calls (ADR-0004); deliver:webhook text is archived as non-resolving STATUS
- **Own oversight policy** -- `ActionRiskClassifier` implementations are injected via CDI; this repo only orchestrates the gate lifecycle
