---
layout: post
title: "The oversight gate closes the loop"
date: 2026-06-09
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-openclaw]
tags: [oversight-gate, action-risk-classifier, qhorus]
---

The gate entry point has been dead code since openclaw#28. When we removed the speech act classification layer, we also removed the code path that decided whether an agent action needed human review. The `casehub_done` MCP tool would accept the agent's claimed completion unconditionally. Phase 2 was always the plan — wire the gate through `CommitmentTools.done()` instead — but it needed `ActionRiskClassifier` to ship in casehub-engine-api first. That shipped as engine#402. So did this.

The shape of the implementation turned out to be simpler than I expected, and harder in exactly one way I didn't.

The simple part: when an agent calls `casehub_done`, we now run the action through whatever `@RiskClassifier ActionRiskClassifier` beans are registered in the CDI container. Zero beans means Autonomous — the commitment closes normally. One or more beans means composition: we replicate the engine's `ChainedReactiveActionRiskClassifier` logic, blocking, because the engine runtime isn't on the classpath here by design. Fewer `candidateGroups` in a `GateRequired` decision is more restrictive (a smaller approval pool, not a larger one) — that's the non-obvious part documented in the garden, and we got it right the first time.

The harder part was the gate context. When `openGate()` decides a human needs to review an action, it opens a COMMAND on the oversight channel. `fulfill()` — called later when the human responds — needs to know which agent commitment to close and which COMMAND message to use as `inReplyTo`. The obvious approach is an in-memory map, but that loses everything on JVM restart. Any gate pending when the server restarts becomes permanently unresolvable.

I went with Java Properties format serialized into the Qhorus COMMAND message content field. The COMMAND is already persisted in Qhorus. The content field is already stored. The parse is five lines of standard library. `Properties.load(new StringReader(content))` handles the timestamp comment that `store()` adds automatically. The gate context survives restart, and the Qhorus ledger entry is human-readable while you're debugging.

The silent bug: `commandMessageId` is computed with `.orElse(-1L)` as a sentinel for "no COMMAND message found." We guard that sentinel correctly in the Autonomous path — `CommitmentTools.channelBacked_done()` returns `COMMAND_NOT_FOUND` before dispatching. But in the gated path, `openGate()` is called before that guard. The sentinel lands in `GateContext`, gets serialized into the Qhorus message, and sits there until `fulfill()` approves the gate and dispatches DONE with `.inReplyTo(-1L)`. Whether Qhorus rejects `-1L` or silently ignores it, the agent's commitment stays open forever. The gate appears to work. The code looks correct at every layer. Claude caught it in review.

The fix is a three-line guard in `openGate()` — but it's a good example of what happens when you have two copies of the same logic (the guard) with one working context (the Autonomous path) and one without (the gated path). The sentinel pattern requires the consumer to check the sentinel before use, and here the consumer is three method calls removed from where the sentinel was assigned.

The final behaviour: approved gate → DONE dispatched to the work channel, agent commitment fulfills atomically with the gate commitment closing. Rejected gate → DECLINE dispatched to the work channel, agent commitment declines. If the JVM restarted between gate open and gate resolve, `parseGateContent()` returns empty, the dispatcher falls back to STATUS on the work channel, and the gate resolves but the agent commitment stays open — acceptable for v1, logged at warn.
