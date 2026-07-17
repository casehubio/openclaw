# Demo UI Blocks Migration Design

**Issue:** #60 (parent) — #66, #67, #68, #69 (children)
**Date:** 2026-07-17
**Status:** Approved

## Overview

Migrate the openclaw demo UI from five hand-rolled Lit components to blocks-ui shared components. The demo UI currently has zero `@casehubio/blocks-ui-*` imports. After migration, three of the five components are replaced by blocks-ui equivalents; two remain local with updated CSS tokens.

## Migration Order

Sequential: #69 (CSS tokens) → #66 (split-workbench) → #67 (channel-feed) → #68 (approval-gate).

CSS tokens first establishes the visual foundation. Each subsequent component swap slots into a consistent token system. No file is touched twice.

## §1 — Theme Bootstrap and CSS Tokens (#69)

### Theme injection

`index.html` gains a `<script>` that calls `injectTheme(DEFAULT_THEME)` and `applyThemeMode(document.documentElement, 'dark')` from `@casehubio/pages-ui-tokens`. This generates all `--pages-*` custom properties on the document root.

### Token mapping

| `--blocks-*` | `--pages-*` |
|---------------|-------------|
| `--blocks-surface` | `--pages-neutral-1` |
| `--blocks-surface-2` | `--pages-neutral-2` |
| `--blocks-surface-3` | `--pages-neutral-3` |
| `--blocks-surface-hover` | `--pages-neutral-4` |
| `--blocks-border` | `--pages-neutral-4` |
| `--blocks-text` | `--pages-neutral-11` |
| `--blocks-text-bright` | `--pages-neutral-12` |
| `--blocks-text-dim` | `--pages-neutral-7` |
| `--blocks-font` | (use `TYPOGRAPHY.family` from pages-ui-tokens) |
| `--blocks-primary` | `--pages-accent-9` |
| `--blocks-primary-hover` | `--pages-accent-10` |
| `--blocks-success-bg` | `--pages-success-9` |
| `--blocks-success-text` | `--pages-success-12` |
| `--blocks-success-hover` | `--pages-success-10` |
| `--blocks-error-bg` | `--pages-danger-9` |
| `--blocks-error-text` | `--pages-danger-12` |
| `--blocks-error-hover` | `--pages-danger-10` |
| `--blocks-warning-bg` | `--pages-warning-9` |
| `--blocks-warning-text` | `--pages-warning-12` |
| `--blocks-info-bg` | `--pages-info-9` |
| `--blocks-info-text` | `--pages-info-12` |

Verify exact token names against current `pages-ui-tokens` source before applying — some may have shifted since #69 was filed.

### Files changed

All five components plus `app-shell.ts`. Remove all hardcoded fallback values — the theme injection guarantees the properties exist.

### app-shell.ts theme toggle

Add `@state() private themeMode: 'dark' | 'light' = 'dark'` to `app-shell.ts`. The current `toggleTheme()` is stateless (`classList.toggle('light')`), but `applyThemeMode()` requires an explicit mode argument. Replace with a method that flips `themeMode` and calls `applyThemeMode(document.documentElement, this.themeMode)` from `pages-ui-tokens`.

### .npmrc

`app/src/main/webui/.npmrc` configures the `@casehubio` scope for GitHub Packages. Added `//npm.pkg.github.com/:_authToken=${GITHUB_TOKEN}` for authentication (matching claudony's pattern).

### package.json

Add: `@casehubio/pages-ui-tokens`

## §2 — Layout: split-workbench (#66)

### Changes to case-execution-view.ts

- Import `@casehubio/blocks-ui-split-workbench`
- Remove inline CSS grid layout: `.layout`, `.panel`, `.panel-content`, the `@media (max-width: 1024px)` breakpoint, and `h3` styles
- Replace layout markup with `<split-workbench>`:
  - `header` slot: status banner
  - `list` slot: `<case-worker-pipeline>`
  - `detail` slot: `<channel-feed>`

### selectionTopic

Openclaw's monitoring view always shows both panels — it is not a list→detail selection flow. Set `selectionTopic` to `"openclaw-scenario"` so the component initialises.

On narrow viewports (container query `max-width: 768px`), split-workbench hides the detail pane until a `${selectionTopic}:selected` pages event fires. Since openclaw never emits selection events naturally, dispatch `emitPagesEvent(document, 'openclaw-scenario:selected', {})` in `firstUpdated()` to activate the detail pane immediately. Import `emitPagesEvent` from `@casehubio/blocks-ui-core`.

### index.ts

Add `import '@casehubio/blocks-ui-split-workbench'`.

### Untouched

SSE orchestration, state management, `loadState()`, `handleSSEEvent()` — all unchanged. This is purely a layout swap.

### Gains

Draggable divider, responsive collapse at narrow widths, localStorage-persisted divider position.

### package.json

Add: `@casehubio/blocks-ui-split-workbench`

## §3 — Channel Feed: channel-activity (#67)

### Delete

`channel-feed.ts` (118 lines) — replaced by `<channel-feed>` from `@casehubio/blocks-ui-channel-activity` (same tag name, drop-in registration).

**Custom element collision:** both the local `channel-feed.ts` and the blocks-ui package register as `channel-feed`. `customElements.define()` throws on duplicate names. The local import in `index.ts` must be removed **before** adding the blocks-ui import. During development, never import both simultaneously.

### Adapter function

Add `toQhorusMessage(event: ChannelMessageEvent, index: number): QhorusMessage` in `case-execution-view.ts`:

| Source (`ChannelMessageEvent`) | Target (`QhorusMessage`) |
|-------------------------------|--------------------------|
| `agentId` | `sender` |
| `occurredAt` | `createdAt` |
| `role` | `actorType` → `'AGENT'` (all openclaw messages are agent-sourced) |
| `content` | `content` |
| — | `messageType` → `'STATUS'` |
| — | `id` → monotonic counter (`let nextId = 0; ... String(nextId++)`) |
| — | `channelId`, `topic` → `''` |
| — | `replyCount` → `0` |
| — | `artefactRefs` → `[]` |
| — | remaining optional fields → `undefined` |

### Template change

The `<channel-feed>` tag stays the same. The `.messages` property now receives `QhorusMessage[]` via the adapter instead of `ChannelMessageEvent[]`.

### Not used

Reactions, commitments, threading — left at defaults. Available when the backend evolves.

### index.ts

Remove `import './components/channel-feed.js'`. Add `import '@casehubio/blocks-ui-channel-activity'`. The blocks-ui import must appear **after** the local import is removed to avoid custom element name collision.

### Gains

Sender grouping, auto-scroll, stale cursor detection, markdown rendering.

### package.json

Add: `@casehubio/blocks-ui-channel-activity`

## §4 — Approval Gate: approval-gate + backend adapter (#68)

### Frontend: delete and replace

Delete `gate-approval-modal.ts` (262 lines).

In `case-execution-view.ts`, replace `<gate-approval-modal>` with `<approval-gate>` inside a `<pages-modal>` from `@casehubio/pages-primitives`. The `<pages-modal>` provides body scroll locking, focus trap (via `FocusTrapMixin`), focus restoration, Escape key handling, backdrop rendering, and `<dialog>` accessibility. No custom modal CSS or JavaScript is needed.

```html
<pages-modal
  variant="alertdialog"
  no-close-button
  .open=${!!this.pendingGate}
  @pages-modal-cancel=${(e: Event) => e.preventDefault()}
>
  <span slot="header">Oversight Gate</span>
  <approval-gate ...></approval-gate>
</pages-modal>
```

Set `variant="alertdialog"` and `no-close-button` because gate decisions require deliberate action. Prevent the `pages-modal-cancel` event so Escape cannot dismiss the modal without a decision — `pendingGate` drives visibility exclusively.

### approval-gate configuration

| Property | Source |
|----------|--------|
| `gate-id` | `gate.gateId` |
| `endpoint` | `/api/scenarios/${scenarioId}` |
| `prompt` | `gate.action` |
| `context-text` | `gate.classification` |
| `outcomes` | default approve/reject pair (no override needed) |
| `data` | `{ agent: gate.agentId, priorAgents: gate.priorAgents }` |

### Event handling

Listen for `gate.decided` using the pages event API — `approval-gate` emits via `emitPagesEvent` (topic-based `CustomEvent('pages-event')`), not standard DOM events. A bare `document.addEventListener('gate.decided', ...)` will never fire.

```typescript
import { onPagesEvent } from '@casehubio/blocks-ui-core';

// In connectedCallback — subscribe:
this._unsubGateDecided = onPagesEvent(document, 'gate.decided', () => {
  this.pendingGate = null;
});

// In disconnectedCallback — unsubscribe:
this._unsubGateDecided?.();
```

SSE `GATE_RESOLVED` also clears `pendingGate`, but the pages event gives instant UI feedback.

### Removed from case-execution-view

Keydown/Escape handler, focus trap, submitting state — `<pages-modal>` handles focus trap, body scroll locking, and Escape; `approval-gate` handles submission state and confirmation dialog internally.

### Backend: new endpoint

`PUT /api/scenarios/{scenarioId}/workitems/{gateId}/complete`

- Request body: `{ outcome: "approve" | "reject", resolution?: string }`
- Maps `outcome` to existing `OversightGateService.fulfill()` logic
- `@RolesAllowed(OpenClawGroups.ADMIN)` (matches existing gate endpoints)
- Returns 200 on success, 404 if gate not found

### Backend: remove old endpoints

Remove `POST .../approve` and `POST .../reject` from `ScenarioRestResource.java` and their tests in `ScenarioRestResourceTest.java`. After migration, their only consumer (`gate-approval-modal.ts`) is deleted — they are dead code. No end users means no backwards compatibility concern.

### index.ts

Remove `import './components/gate-approval-modal.js'`. Add `import '@casehubio/blocks-ui-approval-gate'` and `import '@casehubio/pages-primitives'` (registers `<pages-modal>` custom element).

### Gains

Quorum progress, SLA countdown, decision history, evidence display, focus trap, confirmation dialog, live region announcements.

### package.json

Add: `@casehubio/blocks-ui-approval-gate`

## §5 — Files Unchanged

| File | Changes |
|------|---------|
| `case-worker-pipeline.ts` | CSS tokens only (#69) |
| `demo-launcher.ts` | CSS tokens only (#69) |
| `app-shell.ts` | CSS tokens + theme toggle update (#69) |
| `types/events.ts` | No changes — adapter bridges to `QhorusMessage` |

## Dependencies Added

| Package | Version | Used by |
|---------|---------|---------|
| `@casehubio/pages-ui-tokens` | ^0.2.0 | Theme injection, CSS tokens |
| `@casehubio/blocks-ui-split-workbench` | ^0.1.0 | Layout (#66) |
| `@casehubio/blocks-ui-channel-activity` | ^0.1.0 | Channel feed (#67) |
| `@casehubio/blocks-ui-approval-gate` | ^0.1.0 | Gate approval (#68) |
| `@casehubio/pages-primitives` | ^0.2.0 | `<pages-modal>` for gate modal shell (#68) |

## Testing

- **Visual:** run `quarkus:dev`, verify each migration step renders correctly in the browser
- **SSE:** start a scenario, verify events flow through to blocks-ui components
- **Gate:** trigger an oversight gate, verify `approval-gate` renders in modal, approve/reject calls the new backend endpoint
- **Theme:** toggle light/dark, verify all tokens resolve correctly
- **Responsive:** narrow the viewport, verify split-workbench collapses
- **Backend:** unit test for `PUT /workitems/{gateId}/complete` endpoint mapping to `OversightGateService.fulfill()`
- **E2E:** Playwright automation deferred to #59 — update E2E tests after migration to exercise blocks-ui components
