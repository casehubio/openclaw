---
layout: post
title: "Deleting the speech act layer"
date: 2026-06-08
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-openclaw]
tags: [qhorus, oversight-gate, mcp-tools, speech-act, architecture]
---

Issue #28 was filed as a narrow fix: inject the speech act protocol text into the COMMAND message via `OpenClawChannelBackend.post()` instead of loading it through the always-active `casehub-global` skill. A few lines of code. Scoped clearly. I thought it would take a morning.

The brainstorming session went somewhere else entirely.

The protocol text problem — non-case-step agents receiving instructions that didn't apply to them — was real. But when I looked at why the protocol existed in the first place, the answer was uncomfortable. The `SpeechActClassifier` parsed agent text output (`[DONE]` prefixes, JSON envelopes) to determine which Qhorus message type to dispatch. Underneath that, `casehub_done` was already dispatching typed `DONE` messages directly to Qhorus, correctly, with no parsing required. Two paths to the same result, uncoordinated.

The second path created a real bug. Agents that called `casehub_done` and then produced natural text output would leave `OversightGateService.evaluate()` in a confusing state: no open commitment found (it was already fulfilled), "Watchdog may have expired" logged, a spurious STATUS dispatched. The service couldn't distinguish "commitment closed by tool call" from "Watchdog expired it" — both looked the same.

So: narrow fix (just move the injection point), broader fix (establish tool calls as primary, fix the dual-path conflict), or delete the whole classification layer and treat webhook text as archival only. I asked which approach, expecting the middle option. "No technical debt, straight to 3."

That meant deleting ten production classes: `SpeechActClassifier`, `DefaultSpeechActClassifier`, `SpeechActContext`, `SpeechActResult`, `SpeechActDetection`, `DetectionTier`, `ActionRiskClassifier`, `DefaultActionRiskClassifier`, `PlannedAction`, `RiskDecision`. The classification pipeline — which had taken the entire C8 epic to build — gone.

`OversightGateService.evaluate()` went from 140 lines (classify → risk-assess → dispatch typed message or open gate) to 12 lines: if the output is non-blank, dispatch it as STATUS. That's it. No classification, no commitment lookup, no gate path.

The STATUS dispatch is non-resolving by design. We pulled the Qhorus `MessageService.dispatch()` bytecode and found the guard: `if (dispatch.correlationId() != null)` wraps the entire commitment state transition switch. STATUS without a correlationId bypasses the switch entirely — no `commitmentService.acknowledge()`, no state change. The message is persisted and fanned out, but it touches nothing in Qhorus's commitment lifecycle. That's exactly what archival text should do.

The other half of the change: inject a fully-resolved commitment context block into every case step COMMAND. `OutboundMessage.correlationId()` is the COMMAND's correlationId — which is the commitmentId. We extract it in `OpenClawChannelBackend.post()` and append six concrete tool calls to the message before invocation:

```
Complete  → casehub_done("finance-agent", "abc-123-def-456", outcome)
Decline   → casehub_reject("finance-agent", "abc-123-def-456", reason)
Progress  → casehub_checkpoint("finance-agent", "abc-123-def-456", note)
Escalate  → casehub_escalate("finance-agent", "abc-123-def-456", reason, toAgent?)
Delegate  → casehub_delegate("finance-agent", "abc-123-def-456", reason, toAgent)
Block     → casehub_block("finance-agent", "abc-123-def-456", reason, blockedUntil)
```

Both the agentId and commitmentId are substituted at build time. The agent has the call it needs in its context and doesn't need to call `casehub_commit` just to discover the commitmentId.

One thing caught me during implementation. I tried dispatching a COMMAND with correlationId through the spy'd `MessageService` in `@QuarkusTest` to verify the injection path end-to-end. Zero interactions with the gateway mock — the agent was never invoked. The same dispatch without correlationId worked fine. The difference is that a COMMAND with correlationId triggers `commitmentService.open()` inside Qhorus's `dispatch()`, which interacts with the H2 test environment in a way that prevents `channelGateway.fanOut()` from being reached. The exception gets silently swallowed by a try-catch around `fanOut()`. No error, no clue. The fix was to inject `OpenClawChannelBackend` directly and call `post()` with a hand-constructed `OutboundMessage` — bypassing `dispatch()` entirely. The injection lives in `post()`, so that's the right thing to test anyway.

ADR-0003, which documented the STATUS fallback decision for the old classification pipeline, is now superseded by ADR-0004.

What this opens up: Phase 2 gate wiring (openclaw#30) will go through `CommitmentTools.done()` rather than the webhook path. That's the right place for a gate — the agent is explicitly completing, not just producing text. The oversight review can intercept there before the commitment closes, which gives it a cleaner semantic than intercepting an already-committed text output.
