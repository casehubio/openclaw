---
layout: post
title: "Layer 3 Lifecycle Skills and a DONE Dispatch Fix"
date: 2026-06-04
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-openclaw]
tags: [casehub, openclaw, mcp, quarkus, qhorus]
---

The real design work this session happened before any code was written. The spec went through three review rounds and came back substantially different each time — not style tweaks, actual structural errors.

The most consequential catch: the DONE dispatch fix I originally designed stored `agentId → correlationId` in `OpenClawAgentRegistry` at `ChannelBackend.post()` time, then read it back in `OversightGateService.evaluate()`. Clean-looking, but it put turn-scoped transient state into a session-scoped registry. When the reviewer pointed that out, it also pointed to the better answer: the Commitment is already in Qhorus. `commitmentStore.findOpenByObligor(agentId, workChannelId)` gives you the correlationId directly from the durable store, no in-memory state at all. Restart-resilient as a side effect. I'd overthought it.

The second good catch was `casehub_delegate`. My first design reused `casehub_escalate` with a SKILL.md wrapper that told the LLM it was "for delegation not escalation." Same tool, different prompt. The reviewer pointed out that both produce structurally identical HANDOFF records in the Qhorus ledger — you can never reconstruct from audit history which was intentional delegation and which was authority escalation. Making `toAgent` required on `casehub_delegate` (vs optional on `casehub_escalate`) is the only way to make the distinction machine-readable. That's the kind of thing that's obvious once said, impossible to unsay once done.

`casehub_block` had its own interesting wrinkle. I wanted `@Transactional` directly on the `@Tool` method — unlike every other tool method in `CommitmentTools`, which delegates to already-`@Transactional` service methods. The condition for safety is specific: the method must have no internal try/catch. If you catch an exception inside `@Transactional`, ARC marks the transaction rollback-only, and the subsequent commit attempt throws `TransactionalException` even though the exception was swallowed. `casehub_block` has no try/catch. `OversightGateService.fulfill()` does — which is exactly why we introduced `OversightGateDispatcher` in the previous session. The condition is clean once you see it.

The DONE dispatch STATUS fallback also got refined. I originally described a single fallback path: "no open COMMAND commitment — log STATUS." But there are actually two distinct situations. If `findOpenByObligor()` returns empty, the Watchdog probably expired the commitment while the agent was running — that's WARN, an expected edge case. If the query returns a Commitment but the COMMAND message lookup fails, that's a data inconsistency — the commitment exists but its source message is gone. That's ERROR and needs operator attention. The distinction matters for alerting: you want different severity for each.

Three issues shipped: #23 (lifecycle skills), #24 (ActionRiskClassifier contract confirmation — javadoc only), #16 (DONE dispatch). `qhorus#250` filed to track `CommitmentService.extendDeadline()` — the right long-term fix for `casehub_block`'s current direct `expiresAt` mutation workaround.
