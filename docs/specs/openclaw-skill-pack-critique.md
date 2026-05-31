# CaseHub Skill Pack — Critical Analysis

**Status:** Design critique — written before implementation to surface failure modes
**Epic:** casehub/openclaw#7
**Date:** 2026-05-31

This document captures honest critique of the skill pack design before implementation.
The goal is not to kill the idea but to identify where the design is fragile, where
the claims don't hold, and what needs to change before the skills will work in practice.

---

## The Central Contradiction

**The pitch:** add accountability to your existing 5,400 OpenClaw skills.

**The reality:** `casehub-commit` and `casehub-done` — the most novel skills — must be
called from *within other skills' instruction blocks*. Existing OpenClaw skills don't have
CaseHub calls in them. casehubio/openclaw doesn't own those skills and cannot modify them.

This means the commitment lifecycle only works for skills written specifically with CaseHub
in mind. That is not 5,400 skills. That is zero, until someone writes them.

The "5,400 skills" value proposition is a category error. Those skills gain nothing by
installing this pack. The claim in the README that this "makes CaseHub's accountability
layer accessible to OpenClaw's entire 5,400-skill ecosystem" is false for the commitment
lifecycle skills. It is only true for the stateless wrapper skills (`casehub-workitem`,
`casehub-status`, `casehub-queue`) where the user invokes the skill directly.

---

## 1. LLM Reliability — the Commitment Lifecycle Depends on a Fragile Actor

`casehub-commit` and `casehub-done` require the LLM to execute a stateful protocol:

```
receive COMMAND → call casehub-commit → do work → call casehub-done
```

In the correct order. Every time. Without dropping state between steps.

LLMs are not reliable state machines. Failure modes observed in practice:

- **Premature done:** the agent calls `casehub-done` because it believes the task is
  complete, but the underlying work is still pending (e.g., waiting for an external API).
- **Missed done:** the agent errors mid-task and terminates without calling `casehub-done`.
  The Watchdog fires on a task that legitimately failed. The escalation is correct in the
  sense that nothing was confirmed — but the agent has no way to close it cleanly.
- **Duplicate commit:** the agent calls `casehub-commit` twice on the same COMMAND because
  the first call's response was ambiguous or lost.
- **Skipped commit:** the agent judges the task too simple to warrant a commitment and
  proceeds directly to execution. No Watchdog is armed. No audit record exists.
- **Wrong commitment ID on done:** the agent retrieves the wrong commitment ID from context,
  closing a different commitment or hallucinating an ID entirely.

The consequence: the Watchdog becomes noise. If it fires unpredictably — on legitimate
failures, on premature done calls, on skipped commits — users tune it out. Once users tune
it out, the accountability layer is theatre. The ledger records faithfully; nobody acts on it.

---

## 2. Session Resets Kill Cross-Session Commitment Tracking

OpenClaw resets sessions daily at 4:00 AM local time and on idle timeout. A commitment
opened via `casehub-commit` in session 1 requires a `casehub-done` from whatever session
runs next.

The commitment ID needs to survive the session reset. The agent in session 2 has no memory
of what session 1 committed to unless:

a. CaseMemoryStore integration is complete (Phase 5 — not designed yet, not shipped), or
b. The commitment ID is injected back into the agent's context at session start (requires
   the `session:start` lifecycle hook — planned in OpenClaw issue #48383, not yet implemented)

Without either mechanism, the Watchdog fires on every commitment that spans a session
boundary. The user receives an escalation for something the agent completed but couldn't
record. If this happens repeatedly, users stop configuring SLAs. The enforcement model collapses.

---

## 3. The Commitment ID Problem

`casehub-done` closes a *specific* commitment by ID. The agent gets this ID from the
response to `casehub-commit`. It must:

1. Receive the response correctly
2. Store the ID in a form it can retrieve later
3. Retrieve the correct ID — not a sibling commitment, not a hallucinated one
4. Call `casehub-done` with the correct ID before the session resets

Under concurrent commitments (multiple open tasks), the agent must track which ID maps to
which task. LLMs under this condition produce hallucinated IDs at meaningful rates.
The ledger records the done event; the done closes the wrong commitment; neither Watchdog
fires cleanly; the audit trail is corrupt.

There is no mitigation for this that doesn't require either structured external memory
(CaseMemoryStore) or reliable tool call receipts persisted across turns — neither of which
is shipped.

---

## 4. `casehub-case` Has an Orchestration Paradox

The spec states: once a case opens, "CaseHub orchestrates subsequent steps — OpenClaw shifts
from autonomous agent to orchestrated executor."

For CaseHub to orchestrate OpenClaw via a CasePlanModel, the plan author must know:
- Which OpenClaw skill handles each step
- The exact trigger phrase or `agentId` to target
- How to map CaseHub plan step outputs to OpenClaw skill inputs

This couples CasePlanModel authoring to knowledge of the OpenClaw skill ecosystem. CaseHub
plan authors become responsible for knowing OpenClaw skill names, trigger semantics, and
the API surface of each skill. That is a significant operational burden that is not
documented and not tooled.

More critically: if CaseHub is already orchestrating OpenClaw via Direction 1
(`POST /hooks/agent` direct call), then `casehub-case` is redundant. The user invokes
`casehub-case` from an OpenClaw agent, which opens a CaseHub case, which calls back into
OpenClaw via Direction 1. This is Direction 1 with an extra LLM-mediated round trip.
Direction 1 alone is more reliable and has fewer moving parts.

The skill is only non-redundant in a pure Direction 2 world — where OpenClaw agents initiate
all CaseHub interactions and CaseHub never calls OpenClaw directly. That is a narrower
use case than the design implies and conflicts with the bidirectional integration model
that the rest of casehub-openclaw is built on.

---

## 5. The Skills That Work vs. The Skills That Are Novel

| Skill | Works reliably? | Novel? | Reason |
|---|---|---|---|
| `casehub-workitem` | ✅ Yes | ❌ No | Stateless REST call; user invokes directly |
| `casehub-case` | ⚠️ Partially | ❌ No | Redundant with Direction 1; orchestration paradox |
| `casehub-queue` | ✅ Yes | ❌ No | Stateless REST call; user invokes directly |
| `casehub-status` | ✅ Yes | ❌ No | Stateless REST call; read-only |
| `casehub-commit` | ❌ Fragile | ✅ Yes | Stateful; LLM reliability; session boundary problem |
| `casehub-done` | ❌ Fragile | ✅ Yes | Commitment ID problem; session boundary problem |
| `casehub-context` | ✅ Yes | ❌ No | Redundant if plugin installed; stateless otherwise |

The skills that work reliably are REST wrappers for actions the user explicitly requests.
The skills that are novel depend on LLM-managed state across turns and sessions.

---

## 6. `casehub-context` is Redundant

If the `casehub-openclaw` plugin (TypeScript) or the Python SDK hook is installed, every
agent turn already receives channel context via `before_prompt_build`. `casehub-context`
provides the same content on explicit request.

The explicit-request path is useful for the case where neither integration is active. But
any user who has installed `casehub-openclaw` the plugin has no reason to install
`casehub-context`. The skill's practical audience is users who want partial integration —
context retrieval without the full plugin.

That is a valid use case but a narrow one, and it makes `casehub-context` a fallback skill
rather than a first-class integration point.

---

## 7. Installation Prerequisites Are a High Bar

Using any of these skills requires:

1. A running CaseHub instance — casehub-engine + casehub-work + casehub-qhorus, all wired
   together and network-accessible from OpenClaw
2. API credentials configured in OpenClaw's integration config
3. The `casehub_rest_client` shared utility available to all skills
4. Understanding of which skill to use when, and what CaseHub concepts (WorkItem,
   CasePlanModel, Commitment, Watchdog) mean

The realistic OpenClaw user who doesn't already run CaseHub will not spin up a
multi-service platform to get accountability for a grocery agent. The prerequisites
eliminate casual adoption — which is the adoption path that would produce the "5,400
skills × CaseHub accountability" network effect.

The addressable audience without the prerequisites already met is effectively zero.
The addressable audience with the prerequisites already met is: CaseHub users who want
OpenClaw agents to self-register commitments rather than having CaseHub register them
externally via Direction 1. That is a valid but narrow use case.

---

## 8. The Shared `casehub_rest_client` Is Undefined

All seven skills depend on a shared `casehub_rest_client` supporting resource. The spec
names it but does not define it. Open questions:

- What language? Python? Bash? TypeScript/Deno? OpenClaw's supporting resources layer
  supports all three.
- How does it receive credentials? From OpenClaw's integration config at runtime? From a
  config file in the skill directory?
- What is its error contract? Does it throw on non-2xx? Return an error struct? How does
  the instruction block handle failures?
- What is the API surface? A generic `call(method, path, body)` function? Or typed helpers
  per endpoint?
- What happens when CaseHub is unreachable? The skill fails at the REST call. The agent
  has no fallback path. The Watchdog does not fire because the commitment was never
  registered.

Until `casehub_rest_client` is designed in detail, none of the seven skills can be
implemented correctly. The instruction blocks cannot reference a client whose interface
is unknown.

---

## 9. API Stability and Versioning

If CaseHub's REST API changes — endpoint paths, request shapes, response fields — all
seven skills break simultaneously. There is no versioning strategy in the current design.
ClawHub skill versions would need to track CaseHub API versions. Users running an older
CaseHub instance would need to install an older skill pack version. The operational burden
of this is non-trivial and completely unaddressed.

---

## 10. The README's Strongest Claims Don't Hold

The README states:

> *"every agent commitment is tracked, every deadline is enforced, every decision is
> ledgered"*

This is only true if the LLM reliably calls the right skills in the right order across
session boundaries with correct ID management. As analysed above, it won't — not reliably,
not without external memory and session continuity infrastructure that isn't shipped.

The claim should be scoped to what actually works: explicitly user-initiated tasks
(`casehub-workitem`, `casehub-queue`), status queries (`casehub-status`), and explicitly
user-opened workflows (`casehub-case`). The commitment lifecycle claim requires
infrastructure that doesn't exist yet.

---

## What Would Make This Work

The critique is not that the idea is wrong. The commitment lifecycle for AI agents is
genuinely novel and valuable. The critique is that the current design delegates state
management to the LLM, which is the wrong actor for that responsibility.

An architecture that fixes the core problems:

1. **CaseHub registers the commitment, not the agent.** When CaseHub fires a direct call
   to OpenClaw (Direction 1), CaseHub opens the Commitment at call time. The agent does
   not need to call `casehub-commit`. The Watchdog is armed before the agent even starts.
   The agent calls `casehub-done` only — one call, no state to track, no ID to retrieve
   (it comes in the initial COMMAND payload).

2. **`casehub-done` becomes the only lifecycle skill.** Direction 1 handles the open.
   The agent handles the close. This halves the LLM state management problem.

3. **Commitment ID is injected, not recalled.** The initial COMMAND payload from
   Direction 1 includes the commitment ID. The agent has it from the start, in context,
   without retrieval. Session resets are survivable because the ID is in the turn's
   message, not in the agent's memory.

4. **The stateless skills ship first.** `casehub-workitem`, `casehub-status`,
   `casehub-queue` work today, reliably, without the above infrastructure. Ship these as
   Epic 7. They demonstrate the value. They build the ClawHub listing. They establish the
   `casehub_rest_client` pattern.

5. **The commitment lifecycle skills ship when Direction 1 is the assumed invocation path.**
   `casehub-done` (not `casehub-commit`) ships as a Phase 2 skill once the Direction 1
   payload is confirmed to include commitment ID. The README is updated to reflect the
   correct split.

---

## Summary

| Problem | Severity | Addressed by current design? |
|---|---|---|
| Commitment lifecycle depends on LLM reliability | High | No |
| Session resets break cross-session commitments | High | No |
| Commitment ID management under concurrency | High | No |
| `casehub-case` redundant with Direction 1 | Medium | No |
| `casehub_rest_client` undefined | High | No |
| 5,400 skills claim is a category error | Medium | No |
| `casehub-context` redundant when plugin installed | Low | No |
| Installation prerequisites eliminate casual adoption | Medium | No |
| API versioning strategy absent | Medium | No |
| README's strongest claims don't hold | Medium | No |
