# 0003 — Speech act default when agent output has no explicit signal

Date: 2026-06-05
Status: Superseded by [ADR-0004](0004-tool-call-first-completion-signaling.md)

## Context and Problem Statement

`DefaultSpeechActClassifier` (C8, openclaw#10) falls back to a default `MessageType` when
agent output contains no JSON envelope or bracket prefix. The choice of fallback directly
determines what happens to an open Commitment when an agent produces unrecognised output:
DONE resolves it; STATUS leaves it open with Watchdog armed.

## Decision Drivers

* A false completion (case step proceeds incorrectly) is invisible and unrecoverable
  without operator intervention
* A stuck commitment (Watchdog fires, escalates) is visible and recoverable via the
  defined escalation path
* In Phase 2+3, agents are explicitly trained to signal speech act type — a missing
  signal is a protocol violation, not a known-safe default
* The DONE fallback was correct in Phase 1 (agents had no way to signal type, every
  completion was DONE by convention) but is incorrect once explicit signalling exists

## Considered Options

* **Option A — DONE fallback** — unrecognised output resolves the Commitment as fulfilled
* **Option B — STATUS fallback** — unrecognised output leaves the Commitment open,
  Watchdog stays armed
* **Option C — FAILURE fallback** — unrecognised output resolves as failed

## Decision Outcome

Chosen option: **Option B — STATUS fallback**, because a false completion (case step
closes incorrectly, silently) is a worse failure mode than a stuck commitment
(Watchdog fires, operator investigates, case stays alive).

### Positive Consequences

* Agents that forget the prefix leave the case open rather than silently closing it
* Watchdog escalation is a visible, actionable signal that the agent needs updating
* The deployment/migration note is enforced by behaviour: non-prefixed agents trigger
  escalations immediately, making the rollout risk tangible rather than hidden

### Negative Consequences / Tradeoffs

* Agents that genuinely complete a task but omit the prefix trigger Watchdog escalations
* Every existing case-step agent must be updated to prefix output before shipping C8
* A phased rollout (code before agent update) is unsafe — both must ship atomically

## Pros and Cons of the Options

### Option A — DONE fallback

* ✅ Backward-compatible with Phase 1 agents that don't prefix output
* ✅ No Watchdog noise for non-prefixed completions
* ❌ False completions are invisible — case step closes incorrectly with no signal
* ❌ Perpetuates the Phase 1 assumption that output without a signal means "done"

### Option B — STATUS fallback

* ✅ False completions are prevented — case stays open when signal is missing
* ✅ Watchdog escalation provides a visible recovery path
* ✅ Operationally enforces the protocol: silent agents generate noise, motivating updates
* ❌ Watchdog escalations for non-prefixed agents until skill updates ship
* ❌ Requires atomic deployment of code + agent skill updates

### Option C — FAILURE fallback

* ✅ Stronger signal that something is wrong
* ❌ Resolves the Commitment terminally — no recovery path
* ❌ Would mark every non-prefixed completion as a failure, including valid completions
  by agents that haven't been updated yet

## Links

* casehubio/openclaw#10 — C8 speech act classification
* `docs/specs/2026-06-05-c8-speech-act-classification-design.md` §Fallback Design Decision
