# Demo UI — Design Spec

**Date:** 2026-06-30
**Issue:** casehubio/openclaw#58

## Problem

casehub-openclaw has three demo scenarios (trading-oversight, multi-agent-dev-team, incident-response) that run headless. The only interaction is `scenario.sh` (curl) to start and `approve.sh` to approve oversight gates. There is no way to watch agents progress, see channel messages flow, or experience the oversight gate moment visually.

This makes the demos unusable for pitches, conference talks, or hands-on evaluation. The accountability and oversight story — CaseHub's core differentiator — is invisible without a UI.

## Solution

A casehub-pages UI embedded in the openclaw Quarkus app via Quinoa. Real-time WebSocket updates drive a live dashboard showing agent pipelines, channel messages, commitment lifecycle, and oversight gate approval — all rendered with the TypeScript DSL using standard casehub-pages components.

Single JAR deployment. `mvn quarkus:dev` hot-reloads both Java and TypeScript. The Docker Compose examples serve the same UI.

## Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Flagship scenario | Trading Oversight | Finance + AI is attention-grabbing; dollar amounts make oversight gates visceral |
| Scenario depth | All three at full depth | Same components render any scenario — cost is data, not UI work |
| Real-time transport | WebSocket | casehub-pages has WebSocket dataset support; single connection, multiple datasets |
| Gate approval UX | Modal dialog with provenance | Deliberate interruption; shows the chain of reasoning that led to this moment |
| Landing page | Dashboard overview with sidebar nav | Feels like a real application, not a demo launcher |
| Theme | Dark default with toggle | Control-room aesthetic; `site.setTheme()` handles the toggle |
| Component strategy | Build in openclaw first, extract shared components later | Ship first, abstract second |
| Component model | Standard DSL components (tables, metrics, panels) | No iframe components, no custom Web Components this round |

## Architecture

### Two Layers, One JAR

```
app/src/main/webui/           ← Quinoa TypeScript frontend
  package.json                ← @casehubio/pages-runtime, @casehubio/pages-ui
  tsconfig.json
  esbuild.config.mjs
  .npmrc                      ← GitHub Packages registry for @casehubio scope
  src/
    index.html                ← minimal shell: <div id="app">
    index.ts                  ← loadSite() entry point
    pages/
      dashboard.ts            ← overviewPage()
      scenario.ts             ← scenarioPage(), agentPipeline(), channelFeed(), auditTrail()
    controls/
      gate-modal.ts           ← modal overlay for gate approval
      scenario-controls.ts    ← start button handler
    data/
      datasets.ts             ← all dataset declarations (WebSocket URL, column schemas)
```

### Backend (New Endpoints)

| Endpoint | Purpose |
|----------|---------|
| `WS /ws/events` | Real-time stream: agent state changes, commitment lifecycle, channel messages, gate events |
| `GET /api/scenarios` | List available scenarios with metadata (agents, description, flow) |
| `GET /api/scenarios/{id}/state` | Current state for REST API consumers (e.g. `scenario.sh`). Not used by WebSocket reconnection. |
| `POST /api/scenarios/{id}/start` | Start a scenario asynchronously. Returns 202 immediately; progress via WebSocket. Returns 409 if already running. |
| `POST /api/scenarios/{id}/gate/{gateId}/approve` | Approve an oversight gate (delegates to `OversightGateService.fulfill()`) |
| `POST /api/scenarios/{id}/gate/{gateId}/reject` | Reject an oversight gate (delegates to `OversightGateService.fulfill()`) |

All demo endpoints are `@PermitAll` — no auth for demo simplicity.

**Migration from ExampleController:** `ExampleController` (`POST /example/{id}/start`) is deprecated and removed when the demo UI ships. Its blocking sequencing logic moves to `ScenarioExecutionService` which runs scenarios asynchronously on a managed executor. `scenario.sh` and `approve.sh` scripts updated to use `/api/scenarios/` paths.

**Concurrent run guard:** `ScenarioStateStore` tracks running state per scenario. `POST /api/scenarios/{id}/start` returns 409 Conflict if the scenario is already running.

**OversightGateService dependency:** Gate approval delegates to `OversightGateService.fulfill()` directly. openclaw#31 tracks the planned migration to casehub-blocks (parent#310). When blocks ships, the gate endpoints switch to the blocks API — the migration is mechanical.

### Backend CDI Beans

| Bean | Responsibility |
|------|---------------|
| `ScenarioEventBroadcaster` | Implements `MessageObserver` — passively observes Qhorus dispatches on work/observe/oversight channels, broadcasts wire messages to WebSocket sessions. Same observer pattern as `ChannelContextWindowObserver`. Must never throw. |
| `ScenarioStateStore` | In-memory snapshot of current scenario state — updated by the broadcaster, serves `snapshot` messages on WebSocket connect/reconnect. Tracks running status per scenario for concurrent-run guard. |
| `ScenarioMetadataProvider` | Serves scenario definitions — agents, roles, descriptions, gate config. Maps fixed case IDs to scenario identifiers. |
| `ScenarioExecutionService` | Runs scenario agent sequences asynchronously on a managed executor. Extracted from `ExampleController`'s synchronous loop. Called by `POST /api/scenarios/{id}/start`. |

### Data Flow

```
ScenarioExecutionService sequences agents (ScenarioEventBroadcaster observes via MessageObserver)
  → COMMAND dispatched     → WS: {op:"replace", dataset:"agents", columns:[...], row:[...], key:"signal"}
  → commitment created     → WS: {op:"replace", dataset:"commitments", columns:[...], row:[...], key:"corr-123"}
  → STATUS on work channel → WS: {op:"append",  dataset:"messages", columns:[...], rows:[[...]]}
  → DONE on work channel   → WS: {op:"replace", dataset:"agents", columns:[...], row:[...], key:"signal"}
  → COMMAND on oversight   → WS: {op:"replace", dataset:"gates", columns:[...], row:[...], key:"gate-456"}
                          + WS: {op:"event", topic:"gate-pending", payload:{scenarioId, gateId, ...}}
  → user approves via UI   → POST /api/scenarios/{id}/gate/{gateId}/approve
  → DONE on oversight      → WS: {op:"replace", dataset:"gates", columns:[...], row:[...], key:"gate-456"}
                          + WS: {op:"event", topic:"gate-resolved", payload:{scenarioId, gateId, decision}}
```

`ScenarioEventBroadcaster` implements `MessageObserver` (same pattern as `ChannelContextWindowObserver`). It passively observes all Qhorus message dispatches on work/observe/oversight channels, filtering by the fixed demo case IDs. `ScenarioExecutionService` runs the agent sequence asynchronously — the broadcaster sees the resulting Qhorus events transparently, requiring no coupling between the execution service and the broadcast logic. The WebSocket is the observation channel, not the control channel.

## WebSocket Event Protocol

Single WebSocket connection at `WS /ws/events`. Messages conform to the casehub-pages `WireMessage` interface (`push-source.ts`). Fields per op:

- **snapshot**: `{op:"snapshot", dataset, columns, rows}` — full dataset replace
- **append**: `{op:"append", dataset, columns, rows}` — add rows (2D array)
- **replace**: `{op:"replace", dataset, columns, row, key}` — upsert single row by key
- **remove**: `{op:"remove", dataset, key}` — remove single row by key

Multiple datasets share one connection via the PushSource pool (keyed by base URL).

### Wire Message Types

| Event | op | dataset | columns | key |
|-------|----|---------|---------|-----|
| Agent state change | `replace` | `agents` | scenarioId, agentId, role, state, durationMs, commitmentState, step | agentId |
| Channel message | `append` | `messages` | scenarioId, agentId, role, content, timestamp | — |
| Commitment update | `replace` | `commitments` | scenarioId, commitmentId, agentId, state, outcome, timestamp | commitmentId |
| Gate pending | `replace` | `gates` | scenarioId, gateId, agentId, action, classification, priorAgents (JSON), decision (null), timestamp | gateId |
| Gate resolved | `replace` | `gates` | scenarioId, gateId, agentId, action, classification, priorAgents, decision ("approved"/"rejected"), timestamp | gateId |
| Scenario state change | `replace` | `scenarios` | id, name, status, activeAgent, agentCount | id |
| Activity event | `append` | `activity` | scenarioId, agentId, event, detail, timestamp | — |
| Gate pending trigger | `event` | — | topic: `gate-pending`, payload: `{scenarioId, gateId, agentId, action, classification, priorAgents}` | — |
| Gate resolved trigger | `event` | — | topic: `gate-resolved`, payload: `{scenarioId, gateId, decision}` | — |

### Reconnection

The server sends `snapshot` messages for all datasets in three situations:

1. **WebSocket connect** — new client receives current state from `ScenarioStateStore`
2. **WebSocket reconnect** — casehub-pages `WebSocketSource` reconnects with exponential backoff automatically; the server delivers fresh snapshots on resubscription
3. **Scenario restart** — the broadcaster sends snapshots (empty rows for append-only datasets like `messages`/`activity`, initial state for replace datasets) to all existing WebSocket sessions, clearing stale data from the prior run before the new run begins

No HTTP side-channel needed; state is always delivered over the WebSocket itself.

`GET /api/scenarios/{id}/state` serves REST API consumers (e.g. `scenario.sh`) and is not part of the WebSocket reconnection flow.

Alignment note: casehub-pages has landed the `PushSource` abstraction (branch `issue-81-trailing-s-xs-batch`, spec `2026-06-30-push-source-abstraction-design.md`). The demo UI targets the post-PushSource API surface. `dataset()` declarations use the stable DSL, not the internal `WebSocketSource`/`PushSource` types — the internal refactoring is transparent to DSL consumers.

## Scenario Data Model

### Scenario Metadata (`GET /api/scenarios`)

```typescript
interface Scenario {
  id: string;                    // "trading-oversight"
  name: string;                  // "Trading Oversight"
  description: string;           // One-liner for the overview table
  agents: AgentDef[];            // Ordered sequence
  gateAgentId: string | null;    // Which agent triggers the oversight gate
  status: "idle" | "running" | "completed" | "failed";
}
```

### Scenario Lifecycle State Machine

```
idle ──POST /start──→ running ──all agents terminal──→ completed
                       │  ▲                               │
                       │  └──── POST /start (restarts) ◄──┘
                       │  ▲                               │
                       ├──timeout/exception──→ failed ────┘
                       │
                       └──── 409 on POST /start
```

| Transition | Trigger | Detail |
|-----------|---------|--------|
| `idle` → `running` | `POST /api/scenarios/{id}/start` | ScenarioExecutionService begins async agent sequence. ScenarioStateStore marks running. |
| `running` → `completed` | Last agent reaches terminal CommitmentState | ScenarioExecutionService detects all steps FULFILLED/DECLINED/DELEGATED. |
| `running` → `failed` | Timeout or unrecoverable error | Inherits `casehub.example.timeout.seconds` (default 300). Executor thread interrupted on timeout. |
| `completed`/`failed` → `running` | `POST /api/scenarios/{id}/start` | Scenario restarts from step 1. ScenarioStateStore clears prior state, then broadcasts `snapshot` messages (empty for append-only datasets, initial state for replace datasets) to all existing WebSocket sessions — clears client-side data before the new run begins. 409 only on `running`. |
| JVM restart | Process restart | ScenarioStateStore is in-memory — all scenarios reset to `idle`. No persistence required for a demo. |

```typescript
interface AgentDef {
  agentId: string;               // "signal"
  role: string;                  // "Signal Analyst"
  description: string;           // "Analyses market data for trading signals"
  step: number;                  // 1, 2, 3
}
```

### The Three Scenarios

| Scenario | Agents | Gate Agent |
|----------|--------|------------|
| Trading Oversight | signal → risk → execution | execution |
| Multi-Agent Dev Team | planner → coder → reviewer | reviewer |
| Incident Response | investigator → resolver | resolver |

### Frontend Datasets

All share a single WebSocket connection. Declared in `data/datasets.ts`:

```typescript
const wsUrl = `${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}/ws/events`;

dataset("scenarios",   wsUrl, { keyColumn: "id" }),
dataset("agents",      wsUrl, { keyColumn: "agentId" }),
dataset("messages",    wsUrl),
dataset("commitments", wsUrl, { keyColumn: "commitmentId" }),
dataset("gates",       wsUrl, { keyColumn: "gateId" }),
dataset("activity",    wsUrl),
```

The WebSocket URL is derived from `window.location` — works in `quarkus:dev` (localhost:8080), Docker Compose (any host/port mapping), HTTPS (wss://), and conference demos (remote host).

Datasets receiving `replace` ops must declare `keyColumn` — without it, `processWireMessage` logs a warning and silently drops the message.

| Dataset | Columns | Update pattern | keyColumn |
|---------|---------|----------------|-----------|
| `scenarios` | id, name, status, activeAgent, agentCount | replace | `id` |
| `agents` | scenarioId, agentId, role, state, durationMs, commitmentState, step | replace | `agentId` |
| `messages` | scenarioId, agentId, role, content, timestamp | append | — |
| `commitments` | scenarioId, commitmentId, agentId, state, outcome, timestamp | replace | `commitmentId` |
| `gates` | scenarioId, gateId, agentId, action, classification, priorAgents, decision, timestamp | replace | `gateId` |
| `activity` | scenarioId, agentId, event, detail, timestamp | append | — |

## Frontend Component Composition

### Site Structure

```typescript
page("CaseHub OpenClaw",
  sidebar(
    ["Overview", overviewPage()],
    ["Trading Oversight", scenarioPage("trading-oversight")],
    ["Dev Team", scenarioPage("multi-agent-dev-team")],
    ["Incident Response", scenarioPage("incident-response")],
  ),
  { settings: { mode: "dark" } }
)
```

### Overview Page

```typescript
function overviewPage() {
  return rows(
    columns([3, 3, 3, 3],
      [metric({ title: "Agents", lookup: lookup("scenarios", groupBy(null, sum("agentCount"))) })],
      [metric({ title: "Scenarios", lookup: lookup("scenarios", groupBy(null, count("id"))) })],
      [metric({ title: "Active", lookup: lookup("scenarios",
        filterBy("status", "EQUALS_TO", "running"), groupBy(null, count("id"))) })],
      [metric({ title: "Gates Pending", lookup: lookup("gates",
        filterBy("decision", "IS_NULL"), groupBy(null, count("gateId"))) })],
    ),
    table({
      title: "Scenarios",
      sortable: true,
      lookup: lookup("scenarios"),
    }),
    table({
      title: "Recent Activity",
      pageSize: 15,
      lookup: lookup("activity", sortBy("timestamp", "DESCENDING")),
    }),
  );
}
```

### Scenario Execution Page

Same layout for all three scenarios, driven by data:

```typescript
function scenarioPage(scenarioId: string) {
  return rows(
    panel("Scenario",
      html(`<p>Click start to run the ${scenarioId} demo.</p>
            <button data-scenario-start="${scenarioId}">Start Scenario</button>`),
    ),
    columns([5, 7],
      [agentPipeline(scenarioId)],
      [channelFeed(scenarioId)],
    ),
    auditTrail(scenarioId),
  );
}
```

**Agent Pipeline (left column):** Table showing agent sequence — agent name, role, state (waiting/running/completed), duration, commitment state. Updates in real time via WebSocket.

```typescript
function agentPipeline(scenarioId: string) {
  return panel("Agent Pipeline",
    table({
      lookup: lookup("agents",
        filterBy("scenarioId", "EQUALS_TO", scenarioId),
        sortBy("step", "ASCENDING"),
      ),
    }),
  );
}
```

**Channel Feed (right column):** Scrolling table of agent messages. Newest at bottom.

```typescript
function channelFeed(scenarioId: string) {
  return panel("Channel Feed",
    table({
      pageSize: 50,
      lookup: lookup("messages",
        filterBy("scenarioId", "EQUALS_TO", scenarioId),
        sortBy("timestamp", "ASCENDING"),
      ),
    }),
  );
}
```

**Audit Trail (below split):** Commitment lifecycle table — which commitments were opened, fulfilled, declined. Gate decisions with timing.

```typescript
function auditTrail(scenarioId: string) {
  return panel("Audit Trail",
    table({
      pageSize: 20,
      lookup: lookup("commitments",
        filterBy("scenarioId", "EQUALS_TO", scenarioId),
        sortBy("timestamp", "DESCENDING"),
      ),
    }),
  );
}
```

### Gate Modal

Custom TypeScript outside the casehub-pages component tree (~50 lines). Uses the casehub-pages `pages-event` mechanism for gate notification:

1. When a gate fires, the backend emits both a `replace` on the `gates` dataset (populates the table) and an `{op:"event", topic:"gate-pending", payload:{...}}` message. `processWireMessage` dispatches this as a `CustomEvent("pages-event")` on the configured `eventTarget` (push-source.ts:54-62, `bubbles: true, composed: true`).

2. The gate modal subscribes via `document.addEventListener("pages-event", handler)` and filters for `detail.topic === "gate-pending"`. No direct WebSocket access, no data pipeline internals, no DOM observation.

3. The modal renders an overlay with:
   - Action description and risk classification reason (from event payload `action` and `classification`)
   - Prior agent summary — `priorAgents` lists completed agents with roles and states, assembled by `ScenarioEventBroadcaster` from accumulated Qhorus work channel activity
   - Approve / Reject buttons

4. On decision: POST to `/api/scenarios/{id}/gate/{gateId}/approve` or `/reject`. The backend emits a `{op:"event", topic:"gate-resolved"}` alongside the `replace` update — the modal closes on receiving this event.

casehub-pages lacks a modal/dialog component — tracked in openclaw#61 for filing on casehub-pages.

### Start Button

Custom TypeScript in `scenario-controls.ts`. The scenario panel uses `html()` to render a button with a `data-scenario-start` attribute (see composition above). After `loadSite()`, a delegated event listener on the document root handles clicks on `[data-scenario-start]` elements, firing `POST /api/scenarios/{id}/start`. Event delegation avoids re-attachment issues on page navigation. casehub-pages lacks an action button component — tracked in openclaw#61 for filing on casehub-pages.

### Theme Toggle

`site.setTheme("dark")` / `site.setTheme("light")` via a toggle button in the UI chrome. casehub-pages handles all CSS custom property switching.

## Quinoa Setup

### Maven

```xml
<dependency>
  <groupId>io.quarkiverse.quinoa</groupId>
  <artifactId>quarkus-quinoa</artifactId>
</dependency>
```

### application.properties

```properties
quarkus.quinoa.build-dir=dist
quarkus.quinoa.package-manager-install=true
```

### Build Chain

- `mvn quarkus:dev` — hot-reloads Java and TypeScript
- `mvn package` — single JAR with frontend baked in
- Docker Compose examples use this JAR — no separate frontend container
- No existing `META-INF/resources/` content — clean start

## Testing Strategy

### Backend Tests

| Test | Type | Coverage |
|------|------|----------|
| `ScenarioEventBroadcasterTest` | Unit | Wire message formatting, session management, broadcast |
| `ScenarioStateStoreTest` | Unit | Snapshot update, backfill generation, concurrent access |
| `ScenarioMetadataProviderTest` | Unit | All three scenarios return correct agent sequences and gate config |
| `ScenarioWebSocketTest` | `@QuarkusTest` | WebSocket connection lifecycle, event reception |
| `ScenarioRestResourceTest` | `@QuarkusTest` | GET scenarios, GET state, POST start, POST gate approve/reject |

### Frontend Tests

| Test | Coverage |
|------|----------|
| Dataset column schemas | Each dataset declaration matches the wire message format |
| Gate modal logic | Renders on gate-pending, POSTs correct endpoint, closes on gate-resolved |

### Manual Verification

Run all three scenarios end-to-end and watch the UI. Pipeline updates, messages scroll, gate modal appears, approve/reject works. Visual verification before marking complete.

No Playwright/E2E this round — the UI is a thin declarative layer over tested casehub-pages components. The interesting behaviour (real-time WebSocket flow) is hard to assert without flakiness.

## Shared Component Extraction (Follow-Up)

After the UI is working and polished:

1. Identify which TypeScript functions are genuinely reusable vs scenario-specific
2. Extract reusable patterns (commitment display, agent pipeline, gate modal) into a `@casehubio/pages-casehub` package in the casehub-pages monorepo
3. File an issue on casehub-pages for the new package
4. Refactor openclaw's webui to consume the shared package

This is a separate piece of work — ship the demo first, abstract second.

## Out of Scope

- Custom Web Components or iframe components
- Changes to casehub-pages repo — gaps tracked in openclaw#61 (action button, modal/dialog components)
- Changes to existing delivery/gate/channel infrastructure
- Playwright/E2E test automation — openclaw#59
- Shared component extraction to `@casehubio/pages-casehub` — openclaw#60
- Auth on demo endpoints
