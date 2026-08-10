---
layout: post
title: "The oversight gate nobody asked for yet"
date: 2026-05-30
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-openclaw]
tags: [openclaw, qhorus, oversight, ActionRiskClassifier, BidirectionalWiringIT]
---

Epic 6 was supposed to be the wiring-up epic. COMMAND arrives on a Qhorus work
channel, gets forwarded to OpenClaw via `/hooks/agent`, OpenClaw completes, POSTs
the result back, the result lands on the channel. Most of the pieces existed. I
thought it would take a day.

The bidirectional wiring took about a day. But the design underneath it took longer,
because one question kept expanding.

## The risk gate that belongs in the engine

The issue brief included an oversight gate: when an OpenClaw agent is about to take
a consequential action, pause, route to a human, wait for approval. The question
was where `ActionRiskClassifier` — the thing that decides whether to pause — should
live.

My first instinct was to put it in casehub-openclaw. Finance agents cancelling
subscriptions, booking flights, spending money — who else needs it?

Then I thought about it properly. Claudony runs Claude CLI agents. A devtown
review-agent could merge a PR with a security finding. A clinical agent could
escalate an adverse event. An AML agent could file a SAR with the regulator. Every
one of those is a worker about to take a consequential action that a human should
have the option to stop.

The pattern is platform-wide. The SPI belongs in casehub-engine-api so any worker —
OpenClaw, Claudony, whatever comes next — can use a uniform gate without building
bespoke approval logic.

I filed casehubio/engine#402: `classify(PlannedAction) → RiskDecision`, either
`Autonomous` (proceed) or `GateRequired(reason, reversible)`. Then filed issues on
claudony, devtown, clinical, aml, and life with domain-specific examples. Clinical
alone has several mandatory cases where this matters: SUSAR filing always requires a
clinician to sign off; the ledger record of who approved what is itself a compliance
artefact, not just an audit trail.

For casehub-openclaw in Phase 1: a local `ActionRiskClassifier` interface with a
default that always returns `Autonomous`. The gate is fully wired and tested; it
never fires in production until someone registers a real risk classifier. Javadoc
says the migration to engine-api is a pure import swap.

## Durable by design

`OversightGateService.fulfill()` receives the human's response to a gate and needs
to know which work channel to dispatch to. The gateId in the URL is the key; the
question is what it maps to.

The obvious answer: a `ConcurrentHashMap<UUID, PendingGate>` in memory. O(1) lookup,
simple to reason about.

The problem: if the app restarts while a gate is open, the map is gone. The human's
response arrives to `/openclaw/delivery/oversight/{gateId}`, we find nothing, fail
open, and the case step hangs until Watchdog fires. That's the kind of bug filed six
months later when someone can't explain why a case stalled.

Instead, Claude and I used the Qhorus Commitment that opens automatically when the
gate fires. `MessageService.dispatch(COMMAND, correlationId=gateId)` creates a
`Commitment` in the database. After a restart, `commitmentStore.findByCorrelationId(gateId)`
still works. The oversight channel is the audit trail; Watchdog handles escalation
for free; no new storage needed.

`fulfill()` then looks up the Commitment for the oversightChannelId, extracts the
caseId from the channel name, finds the work channel by name, and dispatches
`RESPONSE` or `DECLINE`. Four indexed queries. But they're against a database that's
already there.

A code review recommended switching to the in-memory map — simpler, fewer lookups,
no external API dependency. I held that position. The in-memory approach solves the
happy path and makes the failure mode worse. The durable path is four queries.

## What the builder actually requires

I expected dispatching `MessageType.DONE` to a work channel to be straightforward.
It's not. `MessageDispatch.Builder.build()` requires both `inReplyTo` (the Long
database primary key of the original COMMAND message) and `correlationId` for DONE,
DECLINE, RESPONSE, and FAILURE. The intent makes sense — resolving a commitment
requires identifying which commitment to resolve. The consequence is that you can't
dispatch DONE from a delivery webhook unless you tracked the original COMMAND's Long
ID at invocation time.

The `OutboundMessage` passed to `ChannelBackend.post()` doesn't expose that ID. It
uses `UUID.randomUUID()` as its own identifier in `fanOut()`. So at delivery time,
we don't have it.

Phase 1 workaround: dispatch `STATUS` instead. STATUS acknowledges the obligation
without resolving it. Not the right type semantically, but the Commitment stays open
and Watchdog handles the hanging case until openclaw#16 is fixed properly.

The gate's own `RESPONSE/DECLINE` dispatches are different — we dispatch the oversight
COMMAND ourselves in `openGate()`, so we can retrieve its Long ID via
`messageService.findAllByCorrelationId(gateId)` and supply the correct `inReplyTo`.
That path dispatches properly.

## The oversight channel gap

The oversight channel had `allowedTypes = "COMMAND,QUERY"`. That's what Claudony's
`NormativeChannelLayout` uses, and we'd copied it. The problem: RESPONSE and DECLINE
— which close the gate Commitment — aren't in that list.

I could have just added them and moved on. But thinking about what the oversight
channel actually needs over time: a human asking for context (QUERY back), an agent
clarifying (STATUS), a telemetry event (EVENT). The COMMAND+QUERY restriction rules
out most of a real oversight conversation.

I set it to null for casehub-openclaw and filed casehubio/claudony#142 asking them
to think hard about whether null, a tiered model (gate channel vs. conversation
channel), or new message types was the right long-term answer. The note to be
genuinely skeptical about the current design, not just patch the gap.

## The test

`BidirectionalWiringIT` is a `@QuarkusTest` with real Qhorus InMemory stores, real
CDI wiring, and the OpenClaw gateway mocked. Register a test agent, dispatch a COMMAND,
verify the gateway gets the right `/hooks/agent` body, POST back to the delivery
endpoint, assert STATUS lands on the work channel. For the gate path: override
`ActionRiskClassifier` via `@InjectMock` to return `GateRequired`, fire the delivery,
trace the second gateway invoke carrying the oversight URL, then POST "approved" to
the oversight endpoint and verify RESPONSE on the oversight channel and DONE on the
work channel.

One wrinkle: `messageService.findAllByCorrelationId()` uses a Panache static call
that goes to H2 directly, bypassing the InMemory store. The InMemory beans are
correctly injected; they just never receive the Panache query. We worked around it
with `@InjectSpy` on `MessageService` — intercept just that method, delegate to the
InMemory store directly.

Nine cases. All green.
