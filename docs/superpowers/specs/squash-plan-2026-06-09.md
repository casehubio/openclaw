# Squash Plan — 2026-06-09

Range: `upstream/main..HEAD` (10 commits → 6 commits)
Branch: `main` on `casehubio/openclaw`

---

## Already Clean — 0 commits
All commits are in action groups.

---

## Group 1 — feat(casehub): OversightGateService.openGate()
*Compaction group — 3 commits → 1*

**Final message:** `feat(casehub): OversightGateService.openGate() — classifier composition + gate COMMAND dispatch

Refs #30`

| Commit | Action | Curated result |
|--------|--------|----------------|
| `7824ce0` feat(casehub): OversightGateService.openGate() — classifier composition + gate COMMAND dispatch | ✅ KEEP | *(see Final message above)* |
| `01bc78f` docs(specs): phase 2 gate wiring design — openclaw#30 | 🔽 SQUASH ↑ | *(absorbed — design spec; semantic home is this implementation commit)* |
| `5e310a0` test(casehub): fulfill() context path tests — DONE/DECLINE dispatch on gate resolution | 🔽 SQUASH ↑ | *(absorbed — test hardening for openGate/fulfill)* |

> **Result:** 1 commit.

---

## Group 2 — feat(casehub): OversightGateDispatcher context-aware dispatch
*Already clean — 1 commit*

✅ KEEP `8a75c93` feat(casehub): OversightGateDispatcher — context-aware DONE/DECLINE dispatch
> *(message adequate — unchanged)*

> **Result:** 1 commit.

---

## Group 3 — feat(casehub): GateDecision and GateContext types
*Already clean — 1 commit*

✅ KEEP `5b7cb9f` feat(casehub): GateDecision and GateContext types for Phase 2 gate wiring
> *(message adequate — unchanged)*

> **Result:** 1 commit.

---

## Group 4 — feat(app): CommitmentTools.done() Phase 2 gate wiring
*Compaction group — 2 commits → 1*

✅ KEEP `c5fa65b` feat(app): CommitmentTools.done() — Phase 2 gate wiring via OversightGateService.openGate()
> Absorbed: `bc944a6` test(app): update OversightGateDispatcherCdiTest comment — openclaw#30 shipped (comment update, proximity)

> **Result:** 1 commit.

---

## Group 5 — fix(casehub): guard commandMessageId=-1L
*Already clean — 1 commit*

✅ KEEP `5191605` fix(casehub): guard commandMessageId=-1L in openGate(); escape pendingReason JSON; restore dispatcher test assertions
> *(message adequate — critical bug fix from code review; standalone identity)*

> **Result:** 1 commit.

---

## Group 6 — Protocol additions
*Compaction group — 2 commits → 1*

✅ KEEP `f48bc15` protocol: PP-20260609-2a04b7 gate-fail-open-asymmetry; PP-20260609-41529d gate-context-sentinel-guard
> Absorbed: `6feea98` docs(protocols): update indexes for PP-20260609-2a04b7 and PP-20260609-41529d (index maintenance)

> **Result:** 1 commit.

---

## AFTER — what `git log --oneline` will show

  10  commits (original)
  -0  pruned by filter-repo
  -4  absorbed by squash
  ──────────────────────────────────────────────
   6  commits — no content lost

Sample (estimated, most recent first):
  f48bc15  protocol: PP-20260609-2a04b7 gate-fail-open-asymmetry; PP-20260609-41529d gate-context-sentinel-guard
  5191605  fix(casehub): guard commandMessageId=-1L in openGate(); escape pendingReason JSON; restore dispatcher test assertions
  c5fa65b  feat(app): CommitmentTools.done() — Phase 2 gate wiring via OversightGateService.openGate()
  7824ce0  feat(casehub): OversightGateService.openGate() — classifier composition + gate COMMAND dispatch
  8a75c93  feat(casehub): OversightGateDispatcher — context-aware DONE/DECLINE dispatch
  5b7cb9f  feat(casehub): GateDecision and GateContext types for Phase 2 gate wiring
