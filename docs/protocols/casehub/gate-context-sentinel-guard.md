---
id: PP-20260609-41529d
title: "Guard sentinel commandMessageId < 0 before constructing GateContext — never persist a sentinel value"
type: rule
scope: repo
applies_to: "casehub-openclaw OversightGateService.openGate() — any code that reads a message ID and embeds it in a persisted record"
severity: critical
refs:
  - docs/specs/openclaw-integration.md
violation_hint: "Passing Optional.empty() fallback or .orElse(-1L) sentinel directly into new GateContext() without a guard — the sentinel is serialized into the Qhorus COMMAND message and later passed to .inReplyTo(-1L), silently breaking commitment lifecycle on gate approval"
garden_ref: "GE-20260609-e53d82"
created: 2026-06-09
---

When `openGate()` looks up the original COMMAND message for the commitment, it uses `.orElse(-1L)` as a sentinel for "not found". This sentinel must be guarded immediately after computation — before it is embedded in `GateContext` and serialized into the Qhorus COMMAND message content. If the sentinel reaches `GateContext`, it is persisted in Qhorus and later passed as `.inReplyTo(-1L)` when `fulfill()` dispatches DONE to the work channel. Whether Qhorus rejects `-1L` or accepts it, the agent's commitment will not close correctly on gate approval. The fix: after `commandMessageId = ... .orElse(-1L)`, check `if (commandMessageId < 0) return new GateDecision.Autonomous()`.
