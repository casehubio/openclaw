# Playwright E2E Test Automation for Demo UI

**Issue:** openclaw#59
**Date:** 2026-07-21
**Status:** Design

---

## Problem

The demo UI is an SSE-driven Lit Web Components dashboard with scenario execution,
agent state tracking, channel activity feeds, and oversight gate approval/rejection.
It has Java-level REST and SSE endpoint tests but no browser-level tests. User-visible
behavior — DOM updates in response to SSE events, modal interactions, theme switching,
SSE reconnection — is untested.

---

## Architecture

A mock HTTP server serves scripted SSE event sequences and REST responses. Playwright
tests load the real Lit UI, which connects to the mock server's `/api/*` routes.
No Quarkus or JVM process is involved in the test loop.

```
Playwright ──▶ Real Lit UI (static file server) ──▶ Mock SSE + REST server
                                                     (scripted CaseExecutionEvents)
```

**Why mock, not real backend:** The `ScenarioExecutionService` orchestrates actual
case execution — provisions OpenClaw agents, polls commitments, routes through Qhorus.
E2E tests need deterministic, fast event sequences that drive the UI through its full
lifecycle. A mock server emitting the same `CaseExecutionEvent` JSON achieves this
without JVM startup, SPI wiring, or external dependencies.

**What's tested:** The real Lit components (`app-shell`, `demo-launcher`,
`case-execution-view`, `case-worker-pipeline`), the real `@casehubio/blocks-ui-*`
shared components (`split-workbench`, `channel-feed`, `approval-gate`), `pages-modal`
from `@casehubio/pages-primitives`, real SSE parsing via `EventSource`, and real DOM
rendering. Everything from the browser's perspective.

---

## File Layout

```
app/src/main/webui/
  e2e/
    fixtures/
      mock-server.ts          — HTTP server: mock SSE + REST endpoints
      events.ts               — factory functions for CaseExecutionEvent JSON
      setup.ts                — globalSetup: build UI + start static file server
      teardown.ts             — globalTeardown: stop static file server
      api-route.ts            — shared Playwright fixture: mock server lifecycle + page.route() wiring
    tests/
      01-overview-start.spec.ts       — load cards, start scenario, status → running
      02-agent-pipeline.spec.ts       — agents appear, transition all outcome states
      03-channel-feed.spec.ts         — messages appear in feed
      04-oversight-gate.spec.ts       — gate modal, confirm dialog, approve/reject
      05-scenario-completion.spec.ts  — status banner → completed/failed
      06-theme-toggle.spec.ts         — dark↔light mode switch
      07-sse-reconnection.spec.ts     — disconnect, reconnect, state backfill
      08-error-handling.spec.ts       — 409 duplicate start, fetch failures, gate errors
    tsconfig.json                     — extends base config, adds @playwright/test types
    playwright.config.ts
```

Colocated with the frontend source in `app/src/main/webui/`. Playwright and its
config are npm-managed alongside the existing esbuild/Lit toolchain. Add
`@playwright/test` to `devDependencies` in `package.json` for pinned, reproducible
CI builds.

The `e2e/tsconfig.json` extends the base config and adds `@playwright/test` types
and Node.js APIs (needed by the mock server). Separate from the base `tsconfig.json`
which has `"include": ["src"]` and DOM-only lib types. The numbered prefixes are an organizational convention. Each test is independently
runnable — every test sets its own preconditions via the mock server API.

---

## Mock Server (`mock-server.ts`)

A lightweight Node.js HTTP server that serves:

**SSE endpoint (`GET /api/scenarios/events`):**
- Accepts `EventSource` connections
- Holds open connections in a `Set<ServerResponse>`
- `emitEvent(event: CaseExecutionEvent)` — push a single SSE `data:` frame to all clients
- `emitSequence(events, intervalMs)` — push a sequence with configurable timing between events
- `disconnectSSE()` — close all SSE connections (simulates network drop)
- `reconnectSSE()` — re-accept connections (clients auto-reconnect via `EventSource` spec)

**REST endpoints:**
- `GET /api/scenarios` — returns `setScenarios(list)` payload (array of `ScenarioDef`)
- `GET /api/scenarios/{id}/state` — returns `setStateSnapshot(id, snapshot)` payload
- `POST /api/scenarios/{id}/start` — records the call, returns 202
- `PUT /api/scenarios/{id}/workitems/{gateId}/complete` — accepts `{outcome, resolution?}` body, records the call, returns 200

**Test control:**
- `setScenarios(list: ScenarioDef[])` — configure the scenarios list response.
  **Note:** The live `GET /api/scenarios` endpoint returns `ScenarioStateSnapshot[]`
  (with `scenarioId`, not `id`, and without `name`/`description`). The mock serves
  `ScenarioDef[]` shape to match what the UI expects. This API/UI contract mismatch
  is a known gap tracked as part of openclaw#58.
- `setStateSnapshot(id: string, snapshot: ScenarioStateSnapshot)` — configure state response
- `reset()` — clear all state, close SSE connections, clear response overrides
- `getRecordedCalls()` — return a log of POST/PUT calls for assertion (start, gate decisions)
- `setNextResponse(path: string, statusCode: number, body?: object)` — override the
  response for the next request matching `path`. Fires once then auto-reverts to the
  default response. Used by error path tests (e.g., 409 on start, 500 on gate complete).
  Cleared by `reset()`.

**Port:** Read from `MOCK_SERVER_PORT` env var, default `3099`. Not hardcoded —
per garden entry `GE-20260429-07114f`, hardcoded ports break Quarkus random-port
test configurations.

---

## Event Factories (`events.ts`)

Factory functions that produce typed `CaseExecutionEvent` JSON matching the
`app/src/main/webui/src/types/events.ts` interfaces. Each factory takes only the
fields that vary; timestamps and scenarioId are defaulted.

```typescript
scenarioStarted(scenarioId?: string): ScenarioStartedEvent
scenarioCompleted(scenarioId?: string): ScenarioCompletedEvent
scenarioFailed(error: string, scenarioId?: string): ScenarioFailedEvent
agentStarted(agentId: string, role: string, scenarioId?: string): AgentStartedEvent
agentCompleted(agentId: string, role: string, outcome: string, durationMs: number, scenarioId?: string): AgentCompletedEvent
channelMessage(agentId: string, role: string, content: string, scenarioId?: string): ChannelMessageEvent
gatePending(gateId: string, agentId: string, action: string, classification: string, priorAgents: string, scenarioId?: string): GatePendingEvent
gateResolved(gateId: string, decision: 'approved' | 'rejected', scenarioId?: string): GateResolvedEvent
commitmentUpdated(agentId: string, commitmentId: string, state: string, outcome: string, scenarioId?: string): CommitmentUpdatedEvent
```

`CommitmentUpdatedEvent` is part of the `CaseExecutionEvent` union type and has a
handler in `case-execution-view.ts` (currently a no-op `// Future: update commitment
state display`). The factory is included for completeness — no test currently exercises
this event type.

Default `scenarioId` is `'trading-oversight'`. Default `occurredAt` is `new Date().toISOString()`.

---

## Setup and Teardown

**`setup.ts` (globalSetup):**
1. Run `node esbuild.config.mjs` to build the Lit UI into `dist/` (one-shot build, no watch/serve — deterministic for tests)
2. Start a static file server for `dist/` on `UI_PORT` (default `3098`)
3. Store static server reference in `globalThis` for teardown

**`teardown.ts` (globalTeardown):**
1. Stop the static file server

**`playwright.config.ts`:**
```typescript
export default defineConfig({
  testDir: './e2e/tests',
  workers: 1,  // sequential — all tests share one mock server instance
  globalSetup: './e2e/fixtures/setup.ts',
  globalTeardown: './e2e/fixtures/teardown.ts',
  use: {
    baseURL: `http://localhost:${process.env.UI_PORT || 3098}`,
  },
  webServer: undefined,  // managed by globalSetup, not Playwright's webServer
});
```

**Mock server + API routing (`api-route.ts`):** Playwright's `globalSetup` runs in
the main runner process. Test files run in worker processes — `globalThis` is not
shared across this process boundary. The mock server is therefore managed by a
worker-scoped Playwright fixture, not globalSetup. With `workers: 1`, the fixture
starts the mock server once for the entire run and provides direct JavaScript access
to mock control methods (`emitEvent`, `setNextResponse`, `reset`, etc.).

The same fixture wires `page.route()` with `route.continue()` to redirect `/api/**`
requests to the mock server. `route.continue()` (not `route.fulfill()`) is essential
— the SSE endpoint requires a persistent streaming connection that `route.fulfill()`
would terminate.

```typescript
// api-route.ts
import { test as base } from '@playwright/test';

export const test = base.extend({
  mockServer: [async ({}, use) => {
    const server = await createMockServer(Number(process.env.MOCK_SERVER_PORT) || 3099);
    await use(server);
    await server.close();
  }, { scope: 'worker' }],

  page: async ({ page, mockServer }, use) => {
    await page.route('/api/**', (route) => {
      const url = new URL(route.request().url());
      url.port = String(mockServer.port);
      route.continue({ url: url.toString() });
    });
    await use(page);
  },
});
```

Tests import `{ test }` from this fixture and receive `mockServer` as a parameter:
```typescript
import { test } from '../fixtures/api-route';

test('scenario start', async ({ page, mockServer }) => {
  mockServer.setScenarios([...]);
  // ...
});
```

---

## Test Specifications

### 01 — Overview → Start scenario

**Precondition:** Mock server has 3 scenarios (trading-oversight, multi-agent-dev-team,
incident-response), all `status: 'idle'`.

- Assert: 3 scenario cards visible, each showing name, description, "idle" badge
- Assert: each card has a "Start" button (not "View")
- Click "Start" on trading-oversight
- Assert: POST to `/api/scenarios/trading-oversight/start` recorded
- Emit `scenarioStarted('trading-oversight')`
- Assert: trading-oversight card status badge changes to "running"
- Assert: Start button replaced by "View" button

### 02 — Agent pipeline progression

**Precondition:** Navigate to case-execution-view for trading-oversight.

- Emit `agentStarted('signal', 'Signal Analyst')`
- Assert: agent card appears with name "signal", role "Signal Analyst", "running" badge
- Emit `agentCompleted('signal', 'Signal Analyst', 'completed', 4200)`
- Assert: badge changes to "completed", duration text "Completed in 4.2s" visible
- Emit `agentStarted('risk', 'Risk Assessor')`
- Assert: second agent card appears, first still shows completed
- Emit `agentCompleted('risk', 'Risk Assessor', 'failed', 1500)`
- Assert: second agent badge shows "failed", duration text shows "Failed after 1.5s"

**Separate tests for remaining outcomes:**
- Emit `agentStarted` → `agentCompleted` with `outcome: 'declined'`, `durationMs: 800`
- Assert: badge shows "declined" with warning styling (CSS class `declined`)
- Assert: duration text shows "Declined after 0.8s"
- Emit `agentStarted` → `agentCompleted` with `outcome: 'delegated'`, `durationMs: 1200`
- Assert: badge shows "delegated" with warning styling (CSS class `delegated`)
- Emit `agentStarted` → `agentCompleted` with `outcome: 'timeout'`, `durationMs: 30000`
- Assert: badge shows "timeout" with danger styling (CSS class `timeout`)
- Assert: duration text shows "Timed out after 30.0s"

**Note:** The current UI renders "Completed in X.Xs" for all outcomes with non-null
`durationMs`, regardless of the agent's state. This is a UI bug (openclaw#71).
The tests assert the correct state-appropriate wording to drive the fix.

### 03 — Channel activity feed

**Precondition:** Navigate to case-execution-view.

- Emit `channelMessage('signal', 'Signal Analyst', 'Analysing trade #1234 — USD/EUR 50M')`
- Assert: message visible in the channel-feed component with sender and content
- Emit second `channelMessage('risk', 'Risk Assessor', 'Risk assessment: exposure within limits')`
- Assert: both messages visible in order

### 04 — Oversight gate modal

**Precondition:** Navigate to case-execution-view.

- Emit `gatePending('gate-1', 'execution', 'Execute trade #1234', 'high-value', 'risk')`
- Assert: modal visible with "Oversight Gate" header
- Assert: action text "Execute trade #1234" visible
- Assert: classification text "high-value" visible
- Click "Approve" button
- Assert: confirmation dialog appears (`approval-gate` has `requireConfirmation=true` by default)
- Click confirm in the `blocks-confirm-dialog`
- Assert: PUT to `/api/scenarios/trading-oversight/workitems/gate-1/complete` recorded with body `{outcome: 'approve'}`
- Emit `gateResolved('gate-1', 'approved')`
- Assert: modal dismissed

**Separate test for reject:**
- Same setup, click "Reject" button
- Assert: confirmation dialog appears
- Click confirm in dialog
- Assert: PUT to `/api/scenarios/trading-oversight/workitems/gate-1/complete` recorded with body `{outcome: 'reject'}`
- Emit `gateResolved('gate-1', 'rejected')`
- Assert: modal dismissed

### 05 — Scenario completion

**Precondition:** Navigate to case-execution-view, scenario is running.

- Emit `scenarioCompleted()`
- Assert: status banner text contains "COMPLETED"
- Assert: banner has success styling (CSS class `completed`)

**Separate test for failure:**
- Emit `scenarioFailed('Agent timeout')`
- Assert: status banner text contains "FAILED"
- Assert: banner has failure styling (CSS class `failed`)

### 06 — Theme toggle

**Precondition:** Load app-shell. Default theme is dark.

- Assert: initial background uses dark theme CSS custom property value
- Click "Toggle Theme" button
- Assert: background shifts to light theme value
- Click again
- Assert: reverts to dark theme value

### 07 — SSE reconnection

**Prerequisite:** This test requires an `EventSource.onopen` handler in
`case-execution-view.ts` (and `demo-launcher.ts`) that calls `loadState()` on
reconnection. The current UI uses raw `EventSource` without reconnection backfill —
missed events during disconnect are permanently lost. Tracked as openclaw#72.

**Precondition:** Navigate to case-execution-view.

- Emit `agentStarted('signal', 'Signal Analyst')` — assert agent visible
- Call `mockServer.disconnectSSE()` — SSE connection drops
- Set state snapshot with 2 agents (signal completed, risk running)
- Call `mockServer.reconnectSSE()` — `EventSource` auto-reconnects per spec
- Wait for UI to call `GET /api/scenarios/{id}/state` for backfill
- Assert: UI shows the snapshot state — 2 agents with correct statuses
- Assert: no duplicate agent cards

### 08 — Error handling

**Test: duplicate start returns 409 (race condition):**
- Precondition: Mock server has trading-oversight with status `'idle'` (Start button visible)
- Call `mockServer.setNextResponse('/api/scenarios/trading-oversight/start', 409, {error: 'Already running'})`
- Click "Start" on trading-oversight
- Assert: POST to `/api/scenarios/trading-oversight/start` recorded
- Assert: scenario card status remains "idle" (no `SCENARIO_STARTED` event emitted, no state corruption)
- Note: The UI's only 409 feedback is `console.warn('Scenario already running')` — no visible error indicator. The test verifies no state corruption, not user feedback.

**Test: gate decision failure:**
- Precondition: Navigate to case-execution-view, emit `gatePending` to show modal
- Call `mockServer.setNextResponse('/api/scenarios/trading-oversight/workitems/gate-1/complete', 500, {error: 'Internal server error'})`
- Click "Approve" → confirm in dialog
- Assert: error message visible in `approval-gate` component (CSS class `error`)
- Assert: modal stays open (user can retry)

---

## Run Commands

```bash
# Install Playwright (first time)
cd app/src/main/webui && npx playwright install --with-deps chromium

# Run all E2E tests
cd app/src/main/webui && npx playwright test

# Run a single test file
cd app/src/main/webui && npx playwright test e2e/tests/04-oversight-gate.spec.ts

# Run with UI mode (interactive debugging)
cd app/src/main/webui && npx playwright test --ui

# Run headed (visible browser)
cd app/src/main/webui && npx playwright test --headed
```

---

## What this does NOT do

- **Replace Java endpoint tests** — the existing `ScenarioRestResourceTest`,
  `ScenarioSseResourceTest`, and `ScenarioExecutionServiceTest` cover the backend.
  E2E tests cover the browser layer only.
- **Test against a real Quarkus instance** — the mock server approach is deliberately
  decoupled. Integration with the real backend is a deployment concern.
- **Cross-browser testing** — Chromium only for the first pass. Firefox/WebKit can be
  added to `playwright.config.ts` later.
- **Visual regression** — no screenshot comparison. Assertions are on DOM content and
  CSS classes, not pixel output.
