# 0004 — Completion signaling via MCP tool calls (tool-call-first)

Date: 2026-06-08
Status: Accepted

## Context and Problem Statement

`OversightGateService.evaluate()` classified agent text output (JSON envelope
or bracket prefix) into Qhorus message types to signal commitment completion.
In parallel, MCP tools (`casehub_done`, `casehub_reject`, etc.) dispatched the
same typed messages directly. The dual path was uncoordinated: agents that used
MCP tools and then produced natural text caused a spurious STATUS dispatch and a
false "Watchdog may have expired" warning. The speech act text protocol also
contaminated `casehub-global` (always-active) with instructions irrelevant to
non-case-step agents.

## Decision Drivers

* Text classification is fundamentally fragile — format errors silently fall
  through to STATUS fallback, triggering unnecessary Watchdog escalation
* MCP tool calls (`casehub_done`) already dispatched the same typed messages,
  correctly, with no parsing required
* Two completion paths cannot be coordinated: `OversightGateService` had no
  reliable way to distinguish "commitment closed by tool call" from
  "Watchdog expired it"
* `casehub-global` with `always: true` reached every agent, not only case step agents

## Considered Options

* **Option A (Approach 1) — Narrow injection fix:** inject the speech act protocol
  text into the COMMAND message via `OpenClawChannelBackend.post()`, removing it
  from casehub-global. Keeps text classification as primary completion path.
* **Option B (Approach 2) — Dual-path clarification:** establish MCP tools as
  primary, text classification as fallback. Fix `OversightGateService` to detect
  "already closed by tool call" before classifying text.
* **Option C (Approach 3) — Tool-call-first:** MCP tools are the sole completion
  signaling mechanism. deliver:webhook text is archived as non-resolving STATUS.
  Speech act classification layer deleted entirely.

## Decision Outcome

Chosen option: **Option C — Tool-call-first**, because the speech act text
classification system was solving a problem that already had a better solution
(MCP tool calls). Keeping classification as even a fallback path perpetuates the
dual-path conflict, dead-code classification logic, and fragile text parsing.
The delivery webhook delivering text as archival STATUS is the correct design:
clean separation of signal (tool call) from content (text output).

### Positive Consequences

* Single completion path — no dual-path coordination problem
* Text output can be natural language — no prefix format required
* `OversightGateService.evaluate()` collapses from 140 lines to 12 lines
* commitmentId is injected into the COMMAND message: agents can call `casehub_done`
  directly without needing a prior `casehub_commit` lookup
* casehub-global no longer carries speech act protocol instructions irrelevant to
  non-case-step agents
* Qhorus dispatch semantics are preserved: STATUS without correlationId bypasses
  `commitmentService.acknowledge()` entirely — no state change, purely archival

### Negative Consequences / Tradeoffs

* Agents that relied on text prefix protocol ([DONE], JSON envelope) must be
  updated to use `casehub_done` — deployment must be coordinated
* `NliSpeechActClassifier` (openclaw#27) is obsoleted — work cancelled, not deferred
* `OversightGateService.openGate()` is removed in this pass; Phase 2 gate wiring
  must be re-implemented through `CommitmentTools.done()` (openclaw#30)

## Pros and Cons of the Options

### Option A — Narrow injection fix

* ✅ Minimal change; scopes the protocol correctly
* ✅ Preserves backward compatibility with text-prefix agents
* ❌ Does not fix the dual-path conflict or the spurious STATUS/warn from tool-call agents
* ❌ Text parsing fragility remains

### Option B — Dual-path clarification

* ✅ Fixes the dual-path conflict; MCP tools take priority
* ✅ Backward-compatible fallback for text-prefix agents
* ❌ Keeps dead classification code; complexity of checking "already fulfilled"
* ❌ Two code paths still require coordination and testing

### Option C — Tool-call-first (chosen)

* ✅ Single path; no coordination problem
* ✅ Natural language text output; no agent-facing format requirements
* ✅ Significant simplification: 10 classes deleted, evaluate() collapses to 12 lines
* ❌ Requires coordinated agent update (no text-prefix fallback)
* ❌ Phase 2 gate wiring deferred to openclaw#30

## Links

* casehubio/openclaw#28 — implementation issue
* ADR-0003 — superseded (speech act fallback decision, now moot)
* openclaw#30 — Phase 2 gate wiring through CommitmentTools.done()
* `docs/specs/2026-06-08-tool-call-first-completion-design.md` — full design spec
