# Squash Plan — issue-35-examples
**Range:** upstream/main..HEAD  (10 commits → 2 commits)
**Date:** 2026-06-17

---

## Already Clean — 0 commits

All commits are in action groups.

---

## Group 1 — Examples design spec
*Compaction group — 8 commits → 1*

| Commit | Action | Curated result |
|--------|--------|----------------|
| `8b3b799` docs: add examples design spec — multi-agent-dev-team, trading-oversight, incident-response | ✅ KEEP | *(message adequate — unchanged)* |
| `6bb8650` docs: revise examples design spec — fix 14 review findings, add ExampleController design | 🔽 SQUASH ↑ | *(absorbed — spec iteration 2; all findings addressed in spec text)* |
| `a70b939` docs: revise examples design spec iteration 3 — fix all 13 review-2 findings | 🔽 SQUASH ↑ | *(absorbed — spec iteration 3)* |
| `307957a` docs: revise examples design spec iteration 4 — fix all 6 review-3 findings | 🔽 SQUASH ↑ | *(absorbed — spec iteration 4)* |
| `cbe285e` docs: revise examples design spec iteration 5 — fix all 6 review-4 findings | 🔽 SQUASH ↑ | *(absorbed — spec iteration 5)* |
| `a897765` docs: revise examples design spec iteration 6 — fix all 5 review-5 findings | 🔽 SQUASH ↑ | *(absorbed — spec iteration 6)* |
| `7a7060e` docs: finalise examples design spec — fix stale property name in Section 10 table | 🔽 SQUASH ↑ | *(absorbed — stale-ref fixup, minimal)* |
| `3fffcaf` docs: finalise examples design spec — add @Blocking to ExampleController handler | 🔽 SQUASH ↑ | *(absorbed — final correctness fix, content already in spec)* |

> **Result:** 1 commit — "docs: add examples design spec — multi-agent-dev-team, trading-oversight, incident-response"

---

## Group 2 — Implementation + doc sync
*Compaction group — 2 commits → 1*
**Final message:** `feat(examples): add DemoGateClassifier, ExamplePoller, ExampleSetup, ExampleController + examples/ directory — Closes #35`

| Commit | Action | Curated result |
|--------|--------|----------------|
| `fd58683` feat(examples): add DemoGateClassifier, ExamplePoller, ExampleSetup, ExampleController + examples/ directory | ✅ KEEP | *(see Final message above — adds Closes #35 from absorbed commit)* |
| `9a484d7` docs: sync CLAUDE.md and ARC42STORIES.MD for examples implementation (#35) | 🔽 SQUASH ↑ | *(absorbed — doc sync belongs with implementation; Closes #35 promoted to KEEP message)* |

> **Result:** 1 commit.

---

## AFTER — what `git log --oneline` will show

  10  commits (original)
  -8  absorbed by squash
  ─────────────────────────────
   2  commits — no content lost

Sample (most recent first):
  <sha>  feat(examples): add DemoGateClassifier, ExamplePoller, ExampleSetup, ExampleController + examples/ directory — Closes #35
  <sha>  docs: add examples design spec — multi-agent-dev-team, trading-oversight, incident-response
