---
layout: post
title: "C8 — Teaching OpenClaw agents to say what they mean"
date: 2026-06-05
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-openclaw]
tags: [speech-act, classification, qhorus, watchdog]
---

The Phase 1 placeholder was always going to need replacing: every OpenClaw agent output
dispatched as DONE, regardless of what the agent actually said. If the case step closed
correctly, great. If not — the case silently moved on and you had no idea. C8 fixes this.

The core idea is straightforward. Agent output now goes through a three-tier classifier:
JSON envelope first (`{"type":"DONE","content":"..."}`), bracket prefix second
(`[STATUS] message`), STATUS fallback third. Whatever tier fires strips the classification
metadata before dispatching to Qhorus — so the content field in the channel message is
the actual action description, not a JSON wrapper or a bracketed label.

I went back and forth most on the fallback. Phase 1 defaulted to DONE — every unrecognised
output resolved the commitment as fulfilled. That was correct when agents had no way to
signal their intent. Now they do, so a missing signal is a protocol violation, not a safe
default. I brought Claude in to work through the trade-offs: a false completion (case
proceeds incorrectly, silently) is worse than a stuck commitment (Watchdog fires, operator
investigates, case stays alive). One is invisible; the other is noisy by design. We landed
on STATUS as the fallback. The cost is that non-prefixed agents will trigger escalations
until their SKILL.md is updated — which is why the spec is blunt about atomic deployment.

Code review caught something I'd missed in `openGate()`. When the gate is opened, the
COMMAND message dispatched to the oversight channel is the audit record — what the agent
actually produced. We had originally planned to use the stripped content everywhere, but
that would make it impossible to recover the original output format from the audit trail.
So the gate dispatcher takes two content strings: raw output for the COMMAND, stripped
content for the human-readable oversight prompt. The reviewer sees "Cancel Netflix
subscription", not `{"type":"DONE","content":"Cancel Netflix subscription."}`. The ledger
keeps the raw form.

The `SpeechActDetection` utility is built to be extended. It returns `Optional<SpeechActResult>`;
a future `NliSpeechActClassifier @Alternative @Priority(1)` in a separate module can call
`detect()` for explicit-signal cases and drop through to ML classification when there's no
explicit signal — the same two tiers, the same utility, just a smarter fallback. Jackson
needs strict mode enabled (`FAIL_ON_TRAILING_TOKENS`) or `{"type":"DONE"} extra text` silently
parses as a match; a named gotcha that ended up in the garden.

The skill update in `casehub-global/SKILL.md` closes the loop for agents. The deployment
note in the spec is blunt: code and skill updates must ship together. A phased rollout —
new code first, agent updates second — triggers Watchdog escalations for every agent turn
in between. That's operational risk worth being explicit about.
