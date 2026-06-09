---
id: PP-20260609-2a04b7
title: "openGate() fail-open asymmetry: classifier exception → GateRequired fail-safe; infrastructure failure → Autonomous"
type: rule
scope: repo
applies_to: "casehub-openclaw OversightGateService.openGate() and any future gate-opening code"
severity: important
refs:
  - docs/specs/openclaw-integration.md
violation_hint: "Returning Autonomous when a classifier throws — a crashed classifier is not a signal that the action is safe; it signals the opposite"
garden_ref: "GE-20260609-e53d82"
created: 2026-06-09
---

`openGate()` uses two distinct fail-open policies that must not be conflated. When an `ActionRiskClassifier` throws, the fail-safe is `GateRequired("Classifier error — manual review required", reversible=true, null, null, null)` — not `Autonomous`. A classifier crash means the safety signal is unavailable; proceeding as if the action is safe would silently bypass the governance gate. Contrast this with infrastructure failures (oversight channel not found, COMMAND dispatch throws): these return `Autonomous` because the gate infrastructure itself is not available, not because the action was evaluated. Any new code path in `openGate()` must preserve this distinction explicitly.
