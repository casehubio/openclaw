# Demo UI — Design Spec (Revised)

**Date:** 2026-06-30 (revised 2026-07-06)
**Issue:** casehubio/openclaw#58

## Problem

casehub-openclaw has three demo cases (trading-oversight, multi-agent-dev-team, incident-response) that run headless. No way to watch agents progress, see channel messages flow, or experience oversight gate moments visually. The accountability story — CaseHub's core differentiator — is invisible without a UI.

## Solution

A case execution observer UI embedded in the openclaw Quarkus app via Quinoa. Built with Lit Web Components following the blocks-ui design language. Real-time SSE updates drive a live view of agent pipelines, channel messages, commitment lifecycle, and oversight gate approval.

Components are designed as generic, extensible case execution observers — not demo-specific. They can be promoted to `@casehubio/blocks-ui-*` packages when stable. OpenClaw provides only the demo launcher and domain-specific renderers.

## Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Component model | Lit Web Components | Matches blocks-ui; extensible via slots and render callbacks |
| Theme | blocks-ui OKLCH tokens (`--blocks-*`) | Consistent design language across CaseHub |
| Real-time transport | SSE via blocks-ui `SSEManager` | Matches blocks-ui pattern; simpler than WebSocket; no new dependency |
| Data loading | REST initial load + SSE deltas | blocks-ui pattern (work-item-inbox does the same) |
| Flagship scenario | Trading Oversight | Finance + AI is attention-grabbing; dollar amounts make oversight gates visceral |
| Scenario depth | All three at full depth | Same components render any case — cost is data, not UI work |
| Gate approval UX | Modal dialog with provenance | Deliberate interruption; shows reasoning chain |
| Theme default | Dark with toggle | Control-room aesthetic |
| Case definitions | Demo-specific `ScenarioDef` model | `ScenarioDef` is a lightweight demo presentation record (id, name, description, agents, gateAgentId, caseId). It maps to an engine `CaseDefinition` by `caseId` but carries only what the UI needs. `CaseDefinition` (namespace, capabilities, workers, bindings, milestones, goals, planning strategy, etc.) is far too heavy for demo metadata. `ScenarioMetadataProvider` hardcodes three case setups. |
| Security posture | `@PermitAll` for all demo endpoints | Demo runs behind container network boundary without OIDC configured. This is a regression from `ExampleController`'s `@RolesAllowed(ADMIN)` — justified because the demo UI must work without auth infrastructure. Production deployment requires auth retrofit (openclaw#64). |

## Architecture

### Two Layers, One JAR

**Frontend** (`app/src/main/webui/`):

```
package.json                 ← lit, @casehubio/blocks-ui-core
tsconfig.json
esbuild.config.mjs
.npmrc                       ← GitHub Packages registry for @casehubio scope
.gitignore
src/
  index.html                 ← <div id="app">
  index.ts                   ← theme setup, component registration, app shell
  app-shell.ts               ← top-level layout: sidebar nav + content area
  components/
    case-worker-pipeline.ts  ← agent step list with state/duration (blocks-ui candidate)
    channel-feed.ts          ← scrolling channel message feed (blocks-ui candidate)
    gate-approval-modal.ts   ← oversight gate modal with provenance (blocks-ui candidate)
    case-execution-view.ts   ← composition: pipeline + feed + audit trail
    demo-launcher.ts         ← list cases, start button (openclaw-specific)
  types/
    events.ts                ← SSE event types, CaseExecutionEvent discriminated union
```

**Backend** — split across two modules:
- `casehub/src/main/java/.../casehub/scenario/` — CDI beans (ScenarioStateStore, ScenarioObserver, ScenarioMetadataProvider, ScenarioExecutionService, ScenarioEventListener)
- `app/src/main/java/.../app/scenario/` — JAX-RS resources (ScenarioSseResource, ScenarioRestResource)

This follows the existing module boundary: `ExampleController` (app/) depends on `casehub/` beans. The `CaseExecutionEvent` sealed interface lives in `casehub/` so both modules can use it.

| Endpoint | Purpose |
|----------|---------|
| `GET /api/scenarios/events` | SSE stream: agent state changes, commitment lifecycle, channel messages, gate events |
| `GET /api/scenarios` | List available demo cases with metadata and current status |
| `GET /api/scenarios/{id}/state` | Current state of a running case (for SSE reconnection backfill) |
| `POST /api/scenarios/{id}/start` | Start a demo case. Returns 202. Returns 409 if already running. |
| `POST /api/scenarios/{id}/gate/{gateId}/approve` | Approve an oversight gate. Delegates to `OversightGateService.fulfill(gateId, "Approved")`. |
| `POST /api/scenarios/{id}/gate/{gateId}/reject` | Reject an oversight gate. Delegates to `OversightGateService.fulfill(gateId, "Rejected")`. |

All demo endpoints are `@PermitAll` (see Design Decisions — Security posture). Gate approval endpoints use the same `OversightGateService.fulfill()` path as the existing `OpenClawOversightDeliveryResource` webhook — the commitment lifecycle is unified regardless of whether approval comes from the UI or an external messaging platform.

### Backend CDI Beans

| Bean | Responsibility |
|------|---------------|
| `ScenarioMetadataProvider` | Demo case definitions — agents, roles, case IDs, gate config. Already implemented (Task 1). |
| `ScenarioStateStore` | In-memory state, SSE event broadcast via `ScenarioEventListener`, concurrent-run guard. Already implemented (Task 2). |
| `ScenarioObserver` | `MessageObserver` — filters demo channels, updates state store. Extended from Task 3 to detect gate events on the oversight channel (see § Data Flow). |
| `ScenarioExecutionService` | Async case execution on `@ManagedExecutor`. Replaces `ExampleController`'s synchronous blocking loop. See § ScenarioExecutionService below. |
| `ScenarioSseResource` | JAX-RS SSE endpoint via `@Produces(SERVER_SENT_EVENTS)` returning `Multi<OutboundSseEvent>`. |
| `ScenarioRestResource` | REST endpoints for case listing, start, gate approval. |

### Data Flow

```
ScenarioExecutionService sequences agents on @ManagedExecutor
  ├─ stateStore.updateScenarioStatus(id, "running", agent)   → SCENARIO_STARTED
  │
  │  Per agent (sequential, ordered by AgentDef.step):
  ├─ stateStore.updateAgentState(id, agent, "running", ...)  → AGENT_STARTED
  ├─ ExampleSetup.setupAndDispatch() creates channels, dispatches COMMAND
  │   └─ returns SetupResult(workChannelId, oversightChannelId)
  ├─ stateStore.registerChannel(workChannelId, scenarioId)   → links work channel
  ├─ stateStore.registerChannel(oversightChannelId, scenarioId) → links oversight channel
  ├─ ExamplePoller.checkState(correlationId) polls until terminal
  ├─ stateStore.updateAgentState(id, agent, outcome, ...)    → AGENT_COMPLETED
  ├─ stateStore.updateCommitment(...)                        → COMMITMENT_UPDATED
  │
  └─ stateStore.updateScenarioStatus(id, "completed|failed") → SCENARIO_COMPLETED/FAILED

ScenarioObserver (MessageObserver) watches registered channels:
  ├─ Work channel messages from agents                       → CHANNEL_MESSAGE
  ├─ Oversight channel COMMAND from "openclaw-gate"          → GATE_PENDING
  └─ Oversight channel RESPONSE/DECLINE from "openclaw-gate" → GATE_RESOLVED

ScenarioStateStore broadcasts typed CaseExecutionEvent to ScenarioEventListeners
  → ScenarioSseResource serializes to JSON, pushes to connected browsers via SSE
  → Lit components receive SSEEvent, cast event.data to CaseExecutionEvent, update DOM
```

**Event producers by type:**

| SSE Event | Producer | Mechanism |
|-----------|----------|-----------|
| `SCENARIO_STARTED`, `SCENARIO_COMPLETED`, `SCENARIO_FAILED` | `ScenarioExecutionService` | Direct call to `stateStore.updateScenarioStatus()` |
| `AGENT_STARTED`, `AGENT_COMPLETED` | `ScenarioExecutionService` | Direct call to `stateStore.updateAgentState()` |
| `CHANNEL_MESSAGE` | `ScenarioObserver` | Detects agent messages on registered work channel |
| `COMMITMENT_UPDATED` | `ScenarioExecutionService` | Direct call to `stateStore.updateCommitment()` |
| `GATE_PENDING` | `ScenarioObserver` | Detects COMMAND from `"openclaw-gate"` on registered oversight channel |
| `GATE_RESOLVED` | `ScenarioObserver` | Detects RESPONSE/DECLINE from `"openclaw-gate"` on registered oversight channel |

**Gate event detection:** `ScenarioExecutionService` does not call `OversightGateService.openGate()` — that is called by `CommitmentTools.channelBacked_done()` (the agent's MCP tool) deep in the commitment lifecycle. The execution service has no visibility into gate state. Instead, gate events are detected via the `MessageObserver` SPI: `OversightGateService.openGate()` dispatches a COMMAND on the oversight channel, and `OversightGateDispatcher.dispatch()` dispatches RESPONSE/DECLINE on the same channel. Since both work and oversight channels are registered with the state store, `ScenarioObserver` sees these messages and fires the corresponding state store methods. This decouples the demo state store from production services — no modifications to `OversightGateService` or `CommitmentTools`.

**Channel registration ordering:** `registerChannel()` is called AFTER `setupAndDispatch()` returns, not before. This is safe because `setupAndDispatch()` dispatches the COMMAND to the agent — the agent hasn't responded yet when registration completes. All agent responses (channel messages, gate events) arrive after setup, so no events are missed. `ExampleSetup.setupAndDispatch()` is refactored to return `SetupResult(UUID workChannelId, UUID oversightChannelId)` so the execution service can register both channels.

REST-first, SSE-updates: components call `GET /api/scenarios` on mount for initial state, then subscribe to SSE for real-time deltas. On SSE reconnect, components call `GET /api/scenarios/{id}/state` to backfill.

### ScenarioExecutionService

Replaces `ExampleController`'s synchronous `@Blocking` polling loop with an async model running on `@ManagedExecutor`.

**Execution model:**

1. `start(scenarioId)` validates the scenario isn't already running (`stateStore.isRunning()`), then submits execution to the managed executor.
2. `stateStore.resetScenario(scenarioId)` — clears prior run state (agent states, messages, commitments, gate states) so the UI starts clean. The method already exists on `ScenarioStateStore`.
3. Broadcasts `SCENARIO_STARTED` via `stateStore.updateScenarioStatus(scenarioId, "running", firstAgentId)`.
3. For each agent in the scenario's `AgentDef` list (sequential, ordered by `step`):
   a. `stateStore.updateAgentState(scenarioId, agentId, "running", ...)` — broadcasts `AGENT_STARTED`.
   b. `ExampleSetup.setupAndDispatch(caseId, tenancyId, agentId, ...)` — creates channels (idempotent), dispatches COMMAND. Returns `SetupResult(workChannelId, oversightChannelId)`.
   c. `stateStore.registerChannel(result.workChannelId(), scenarioId)` — links work channel so `ScenarioObserver` routes agent messages.
   d. `stateStore.registerChannel(result.oversightChannelId(), scenarioId)` — links oversight channel so `ScenarioObserver` detects gate events.
   e. Polls `ExamplePoller.checkState(correlationId)` every 2s until terminal state or timeout.
   f. On terminal state, broadcasts `AGENT_COMPLETED` with outcome mapping:
      - `FULFILLED` → `completed`
      - `DECLINED` → `declined`
      - `DELEGATED` → `delegated`
      - `FAILED` → `failed`
      - `EXPIRED` / timeout → `timeout`
   g. `stateStore.updateCommitment(...)` — broadcasts `COMMITMENT_UPDATED`.
   h. Non-`FULFILLED` outcomes stop the pipeline and broadcast `SCENARIO_FAILED`.
4. After all agents complete successfully, broadcasts `SCENARIO_COMPLETED`.

**`ExampleSetup` refactoring:** `setupAndDispatch()` currently returns `void`. Refactored to return `SetupResult(UUID workChannelId, UUID oversightChannelId)` — the channel UUIDs are already computed internally (`caseChannelProvider.openChannel()` returns the channel object), they just need to be exposed. This is a trivial API change.

**Configuration** (reuses existing properties):
- `casehub.example.enabled` — gates execution
- `casehub.example.tenancyid` — demo tenant
- `casehub.example.timeout.seconds` — per-agent timeout
- `casehub.example.gate.agentid` — oversight gate agent

**Gate lifecycle:** `ScenarioExecutionService` does NOT call `OversightGateService.openGate()` and has no direct visibility into gate state. Gates are opened by `CommitmentTools.channelBacked_done()` (the agent's MCP tool) and resolved by `OversightGateService.fulfill()` (via UI or webhook). Both produce messages on the oversight channel that `ScenarioObserver` detects (see § Data Flow — Gate event detection). When a gate is pending, the commitment is non-terminal, so the poller returns null and the execution service continues polling silently until the gate is resolved.

## SSE Event Protocol

Single SSE endpoint: `GET /api/scenarios/events`. The backend sends SSE `data:` frames containing JSON-serialized `CaseExecutionEvent` objects. `SSEManager` parses each frame into an `SSEEvent` — the `CaseExecutionEvent` is the `data` property of `SSEEvent`, not a match for `SSEEvent` itself.

**Protocol layering:**
1. SSE transport: `event: message\ndata: {"type":"AGENT_STARTED","scenarioId":"trading",...}\n\n`
2. `SSEManager` wraps into: `SSEEvent { type: "message", data: { type: "AGENT_STARTED", ... } }`
3. Components receive `SSEEvent`, cast `event.data as CaseExecutionEvent`, switch on `.type`

### Event Types — Discriminated Union

Events use a sealed type hierarchy (Java sealed interface / TypeScript discriminated union) — each event type carries only its relevant fields. No nullable dead weight.

**Java:**

```java
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ScenarioStartedEvent.class, name = "SCENARIO_STARTED"),
    @JsonSubTypes.Type(value = ScenarioCompletedEvent.class, name = "SCENARIO_COMPLETED"),
    @JsonSubTypes.Type(value = ScenarioFailedEvent.class, name = "SCENARIO_FAILED"),
    @JsonSubTypes.Type(value = AgentStartedEvent.class, name = "AGENT_STARTED"),
    @JsonSubTypes.Type(value = AgentCompletedEvent.class, name = "AGENT_COMPLETED"),
    @JsonSubTypes.Type(value = CommitmentUpdatedEvent.class, name = "COMMITMENT_UPDATED"),
    @JsonSubTypes.Type(value = ChannelMessageEvent.class, name = "CHANNEL_MESSAGE"),
    @JsonSubTypes.Type(value = GatePendingEvent.class, name = "GATE_PENDING"),
    @JsonSubTypes.Type(value = GateResolvedEvent.class, name = "GATE_RESOLVED"),
})
public sealed interface CaseExecutionEvent {
    String scenarioId();
    Instant occurredAt();
}

public record ScenarioStartedEvent(String scenarioId, Instant occurredAt) implements CaseExecutionEvent {}
public record ScenarioCompletedEvent(String scenarioId, Instant occurredAt) implements CaseExecutionEvent {}
public record ScenarioFailedEvent(String scenarioId, Instant occurredAt, String error) implements CaseExecutionEvent {}

public record AgentStartedEvent(String scenarioId, Instant occurredAt,
    String agentId, String role) implements CaseExecutionEvent {}
public record AgentCompletedEvent(String scenarioId, Instant occurredAt,
    String agentId, String role, String outcome, long durationMs) implements CaseExecutionEvent {}
    // outcome: "completed", "failed", "declined", "delegated", "timeout"

public record CommitmentUpdatedEvent(String scenarioId, Instant occurredAt,
    String agentId, String commitmentId, String state, String outcome) implements CaseExecutionEvent {}

public record ChannelMessageEvent(String scenarioId, Instant occurredAt,
    String agentId, String role, String content) implements CaseExecutionEvent {}

public record GatePendingEvent(String scenarioId, Instant occurredAt,
    String gateId, String agentId, String action, String classification,
    String priorAgents) implements CaseExecutionEvent {}

public record GateResolvedEvent(String scenarioId, Instant occurredAt,
    String gateId, String decision) implements CaseExecutionEvent {}
```

**TypeScript:**

```typescript
interface BaseEvent {
  readonly scenarioId: string;
  readonly occurredAt: string;   // ISO-8601
}

export interface ScenarioStartedEvent extends BaseEvent { readonly type: 'SCENARIO_STARTED'; }
export interface ScenarioCompletedEvent extends BaseEvent { readonly type: 'SCENARIO_COMPLETED'; }
export interface ScenarioFailedEvent extends BaseEvent { readonly type: 'SCENARIO_FAILED'; readonly error: string; }

export interface AgentStartedEvent extends BaseEvent {
  readonly type: 'AGENT_STARTED';
  readonly agentId: string;
  readonly role: string;
}
export interface AgentCompletedEvent extends BaseEvent {
  readonly type: 'AGENT_COMPLETED';
  readonly agentId: string;
  readonly role: string;
  readonly outcome: 'completed' | 'failed' | 'declined' | 'delegated' | 'timeout';
  readonly durationMs: number;
}

export interface CommitmentUpdatedEvent extends BaseEvent {
  readonly type: 'COMMITMENT_UPDATED';
  readonly agentId: string;
  readonly commitmentId: string;
  readonly state: string;
  readonly outcome: string;
}

export interface ChannelMessageEvent extends BaseEvent {
  readonly type: 'CHANNEL_MESSAGE';
  readonly agentId: string;
  readonly role: string;
  readonly content: string;
}

export interface GatePendingEvent extends BaseEvent {
  readonly type: 'GATE_PENDING';
  readonly gateId: string;
  readonly agentId: string;
  readonly action: string;
  readonly classification: string;
  readonly priorAgents: string;
}

export interface GateResolvedEvent extends BaseEvent {
  readonly type: 'GATE_RESOLVED';
  readonly gateId: string;
  readonly decision: 'approved' | 'rejected';
}

export type CaseExecutionEvent =
  | ScenarioStartedEvent | ScenarioCompletedEvent | ScenarioFailedEvent
  | AgentStartedEvent | AgentCompletedEvent
  | CommitmentUpdatedEvent | ChannelMessageEvent
  | GatePendingEvent | GateResolvedEvent;
```

### Backend Implementation

JAX-RS SSE via `@Produces(MediaType.SERVER_SENT_EVENTS)` returning `Multi<OutboundSseEvent>`. This is the **passive Multi pattern** — Quarkus manages the SSE sink lifecycle. The `SseEventSink` is never held or closed manually, avoiding the sink-close-after-send race documented in protocol `PP-20260613-3a569e` (`sse-sink-async-close`). `ScenarioStateStore` broadcasts typed `CaseExecutionEvent` objects to registered `ScenarioEventListener` instances. The SSE resource serializes them to JSON via Jackson — the store doesn't know about serialization format.

### Reconnection

`SSEManager` (blocks-ui-core) handles exponential backoff automatically. On reconnect, components call `GET /api/scenarios/{id}/state` to backfill current state.

**Backfill response format** (`ScenarioStateSnapshot`):

```typescript
interface ScenarioStateSnapshot {
  readonly scenarioId: string;
  readonly status: 'idle' | 'running' | 'completed' | 'failed';
  readonly agents: AgentState[];         // current state of each agent
  readonly pendingGate: GateState | null; // non-null if a gate is awaiting approval
  readonly recentMessages: ChannelMessageEvent[]; // last N messages (capped at 100)
}

interface AgentState {
  readonly agentId: string;
  readonly role: string;
  readonly state: 'waiting' | 'running' | 'completed' | 'failed' | 'declined' | 'delegated' | 'timeout';
  readonly durationMs: number | null;
}

interface GateState {
  readonly gateId: string;
  readonly agentId: string;
  readonly action: string;
  readonly classification: string;
  readonly priorAgents: string;
}
```

If no scenario is running, the endpoint returns the snapshot with `status: 'idle'`, empty agents, null gate, and empty messages. For the overview (`GET /api/scenarios`), each scenario includes its current status but not detailed state — the client fetches detailed state on navigation.

## Component Architecture

### Generic Components (blocks-ui candidates)

Built as extensible Lit Web Components with `--blocks-*` design tokens. Each has extension points for domain-specific rendering. All components use blocks-ui-core accessibility mixins where applicable — these are required for blocks-ui promotion.

**`case-worker-pipeline`** — Vertical list of workers/agents progressing through steps.
- Properties: `workers`, `renderDetail` (optional render callback)
- Shows: name, role, state (waiting/running/completed/failed/declined/delegated/timeout), duration, commitment state
- Extension: `renderDetail` callback lets the host app add domain-specific worker info
- SSE events: `AGENT_STARTED`, `AGENT_COMPLETED`
- Accessibility: `RovingTabindexMixin` for keyboard navigation between agent steps; `role="list"` with `role="listitem"` children; `LiveRegionMixin` to announce state transitions (e.g. "Risk Assessor completed in 4.2s")

**`channel-feed`** — Scrolling feed of Qhorus channel messages.
- Properties: `messages`, `renderMessage` (optional render callback)
- Shows: sender, role, content, timestamp. Newest at bottom.
- Extension: `renderMessage` callback for custom message formatting
- SSE events: `CHANNEL_MESSAGE`
- Accessibility: `LiveRegionMixin` to announce new messages to screen readers; `aria-label` on feed container

**`gate-approval-modal`** — Modal overlay for oversight gate decisions.
- Properties: gate context (action, classification, prior agents)
- Slot: `gate-detail` for domain-specific content (e.g. trade summary)
- Shows: action description, risk classification, prior agent chain, approve/reject buttons
- Emits: `gate-decision` event with `{gateId, decision}`
- SSE events: `GATE_PENDING` shows modal, `GATE_RESOLVED` closes it
- Accessibility: `FocusTrapMixin` to trap keyboard focus within modal; `role="alertdialog"`, `aria-modal="true"`, `aria-labelledby` pointing to modal title; Escape key dismisses (rejects)

**`case-execution-view`** — Composition of pipeline + feed + audit trail.
- Properties: `scenarioId`, `endpoint`
- Layout: two-column split (pipeline left 40%, feed right 60%), audit trail below
- Subscribes to SSE and distributes events to child components
- Accessibility: `LiveRegionMixin` to announce scenario-level state transitions (started, completed, failed, gate pending)

**All components:** `@media (prefers-reduced-motion: reduce)` for any animations or transitions.

### OpenClaw-Specific Components

**`demo-launcher`** — Lists available demo cases with status and start buttons.
- Calls `GET /api/scenarios` for case list
- Shows: name, description, agent count, status (idle/running/completed)
- Start button: `POST /api/scenarios/{id}/start`
- SSE updates status in real-time

**`app-shell`** — Top-level layout with sidebar navigation.
- Sidebar: Overview (launcher), Trading Oversight, Dev Team, Incident Response
- Content: `case-execution-view` for the selected case, or `demo-launcher` for overview
- Theme toggle (dark/light) via `generateThemeCSS()`

### Component Communication

- `demo-launcher` → `app-shell`: navigation event on case selection
- `case-execution-view` → children: distributes SSE events to pipeline, feed, audit
- `gate-approval-modal` → REST: `POST /api/scenarios/{id}/gate/{gateId}/approve|reject`
- All via `emitPagesEvent()` / `onPagesEvent()` from `@casehubio/blocks-ui-core`

## Extensibility Model

Components use Lit's natural extension patterns — slots, render callbacks, and properties — so domain-specific rendering is pluggable without subclassing.

```typescript
// Generic component with extension point
@customElement('case-worker-pipeline')
export class CaseWorkerPipeline extends LitElement {
  @property({ type: Array }) workers: WorkerState[] = [];
  @property({ attribute: false }) renderDetail?: (worker: WorkerState) => TemplateResult;
}

// OpenClaw uses it with domain-specific detail
html`
  <case-worker-pipeline
    .workers=${agents}
    .renderDetail=${(w) => html`<span class="model">${w.model}</span>`}
  ></case-worker-pipeline>
`
```

This supports future domain accelerators (FSI, clinical, SRE) that provide pre-built renderers for common verticals.

## Quinoa Setup

### Maven

```xml
<dependency>
  <groupId>io.quarkiverse.quinoa</groupId>
  <artifactId>quarkus-quinoa</artifactId>
</dependency>
```

No `quarkus-websockets` needed — SSE is built into `quarkus-rest`.

### application.properties

```properties
quarkus.quinoa.build-dir=dist
quarkus.quinoa.package-manager-install=true
quarkus.quinoa.package-manager-install.node-version=22.15.0
```

### Frontend Dependencies

```json
{
  "dependencies": {
    "lit": "^3.0.0",
    "@casehubio/blocks-ui-core": "0.2.0"
  },
  "devDependencies": {
    "esbuild": "^0.25.0",
    "typescript": "^5.6.0"
  }
}
```

### Build Chain

- `mvn quarkus:dev` — hot-reloads Java and TypeScript
- `mvn package` — single JAR with frontend baked in
- Docker Compose examples serve the same JAR

## ScenarioStateStore Rewrite (Task 2 → typed events)

The existing `ScenarioStateStore` (Task 2) is substantially rewritten — not just a listener interface change.

**What changes:**

| Aspect | Current (WireMessage-based) | Revised (typed events) |
|--------|----------------------------|----------------------|
| Listener interface | `ScenarioEventListener.onWireMessage(String json)` | `ScenarioEventListener.onEvent(CaseExecutionEvent event)` |
| State storage | `List<String>` rows matching column definitions | Typed maps (`Map<String, AgentState>`, etc.) |
| Broadcast payload | `WireMessage.replace()`, `.append()`, `.event()` JSON builders | Typed `CaseExecutionEvent` record construction |
| Snapshot generation | `generateSnapshots()` → `List<String>` of wire message JSON | `currentState(scenarioId)` → `ScenarioStateSnapshot` typed object |
| Column definitions | 6 static `List<WireMessage.Column>` constants | Removed — structure is defined by the record types |

**Scope:** Every mutation method body (`updateAgentState`, `addMessage`, `updateCommitment`, `updateScenarioStatus`, `fireGatePending`, `fireGateResolved`, `addActivity`, `generateSnapshots`, `resetScenario`, `broadcast`) is rewritten. The column-based row storage is replaced with typed state objects. The public API contract (method signatures) is largely preserved — callers still call `updateAgentState(scenarioId, agentId, state, ...)`. What changes is the internal representation and the broadcast format.

The `WireMessage` class (Task 1) is removed. The `CaseExecutionEvent` sealed interface and its record implementations live in the `casehub/scenario` package, serialized to JSON by JAX-RS Jackson.

## Testing Strategy

### Backend Tests

| Test | Type | Coverage |
|------|------|----------|
| `ScenarioSseResourceTest` | `@QuarkusTest` | SSE connection, event reception, reconnection backfill |
| `ScenarioRestResourceTest` | `@QuarkusTest` | GET scenarios, POST start (202/409), POST gate approve/reject |
| `ScenarioExecutionServiceTest` | Unit | Async execution, state transitions, error handling |

### Frontend Tests

Minimal — Lit components are thin wrappers over SSE data. Focus on:
- SSE event routing to correct child components
- Gate modal show/hide lifecycle
- Start button fires correct REST call

### Manual Verification

Run all three demo cases via the UI. Pipeline updates in real time, messages scroll, gate modal appears, approve/reject works. All three scenarios end-to-end.

## Shared Component Promotion Path

After the demo UI is working:

1. Move `case-worker-pipeline`, `channel-feed`, `gate-approval-modal` to blocks-ui as `@casehubio/blocks-ui-*` packages
2. OpenClaw's webui depends on the published packages instead of local files
3. Domain-specific renderers stay in openclaw
4. File issues on blocks-ui for each component promotion

## What Changed From Original Spec

| Aspect | Original (2026-06-30) | Revised (2026-07-06) |
|--------|----------------------|---------------------|
| Component model | casehub-pages TypeScript DSL | Lit Web Components (blocks-ui) |
| Theme | `--pages-*` CSS properties | `--blocks-*` OKLCH tokens |
| Real-time transport | WebSocket with wire messages | SSE via `SSEManager` |
| Data loading | WebSocket datasets with snapshots | REST + SSE deltas |
| Dependencies | `@casehubio/pages-runtime`, `@casehubio/pages-ui` | `lit`, `@casehubio/blocks-ui-core` |
| Maven deps | `quarkus-websockets`, `quarkus-quinoa` | `quarkus-quinoa` only |
| Event format | casehub-pages WireMessage (`{op, dataset, columns, rows}`) | Simple JSON (`{type, scenarioId, ...}`) |
| Extensibility | None (pages DSL is declarative) | Lit slots + render callbacks |
| Platform concept | "Scenarios" as new concept | Demo-specific `ScenarioDef` mapping to engine `CaseDefinition` by `caseId` |

## Out of Scope

- Custom domain accelerators (FSI, clinical) — future work
- CaseDefinition registry integration — `ScenarioMetadataProvider` stays as demo convenience
- Changes to blocks-ui repo — issues filed for component promotion when ready
- Playwright/E2E test automation — openclaw#59
- Auth on demo endpoints — openclaw#64 (security retrofit for production deployment)
