---
layout: post
title: "Consuming blocks-ui — the first real migration"
date: 2026-07-17
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-openclaw]
tags: [blocks-ui, lit, css-tokens, demo-ui]
---

The demo UI started as five hand-rolled Lit components with hardcoded CSS values and its own token system (`--blocks-*`). It worked, but it was carrying 380 lines of layout, channel rendering, and modal management that blocks-ui now handles as shared components. This branch replaced three of the five.

The migration order matters. CSS tokens first — mechanical, but it establishes the visual foundation so every subsequent component swap renders correctly from the start. Then layout (`split-workbench`), channel feed (`channel-activity`), and finally the approval gate (`approval-gate` inside `pages-modal`).

The token migration exposed a genuine gotcha. The spec, the issues, and even the adversarial design review all referenced `--pages-success-contrast`, `--pages-danger-contrast`, and so on. None of these exist. The `pages-ui-tokens` scale generates `--pages-{name}-{1..12}` — twelve steps, no `-contrast` suffix. The naming pattern is borrowed from Radix and Open Props, where contrast tokens are real. Here they're not, and the misconception propagated through four documents before I caught it by reading `colours.ts`. Step 12 turns out to be the right answer for text on colored backgrounds — it's the extreme end of the scale and auto-adapts per theme mode.

The approval gate migration had a design question worth recording. The blocks-ui `approval-gate` component calls `PUT {endpoint}/workitems/{gateId}/complete` — a different API contract from openclaw's original `POST .../approve` and `POST .../reject`. I added the new endpoint and removed the old ones. Pre-release means no backwards compatibility concerns, and the old endpoints had exactly one consumer — the component I was deleting. The new endpoint maps the `{ outcome: "approve" | "reject" }` body to the same `OversightGateService.fulfill()` call.

A surprise along the way: `ScenarioRestResourceTest` was entirely broken — all seven tests returning 401 because `@TestSecurity` was never added. The endpoints had `@RolesAllowed(ADMIN)` but the test class had no security context. Pre-existing, unrelated to the migration, but fixed as part of the backend work.

The blocks-ui event system uses a topic-based pattern (`emitPagesEvent` / `onPagesEvent`) rather than standard DOM events. A bare `addEventListener('gate.decided', ...)` on the document will never fire — the actual event is `CustomEvent('pages-event')` with the topic in `event.detail`. The design review caught this before implementation, which saved a debugging session.

Net result: three local components deleted (380 lines), five shared dependencies added, two local components remain with updated tokens. The demo UI now gets sender grouping, markdown rendering, draggable dividers, focus traps, and SLA countdowns without maintaining any of it.
