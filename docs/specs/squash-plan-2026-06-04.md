# Squash Plan — issue-23-layer3-lifecycle-skills

**Range:** `upstream/main..HEAD`  
**Date:** 2026-06-04  
**Before:** 9 commits → **After:** 5 commits (4 absorbed)

---

## Summary

```
9  commits (original)
-4  docs(spec) iteration commits absorbed into feat
──────────────────────
5  commits — no content lost
```

---

## Already Clean — 4 commits (no action needed)

| Commit | Message |
|--------|---------|
| `b2dc9eb` | docs(arc42): Epic 8 Phase 2 — MCP tool surface, DONE dispatch, lifecycle skills |
| `d29e77b` | fix(evaluate): dispatch DONE (not STATUS) on autonomous path via Qhorus-native query |
| `cec6ea6` | feat(skills): Layer 3 lifecycle SKILL.md — reject, block, delegate |
| `d5e25f1` | docs(ActionRiskClassifier): confirm contract identical to engine#402 as of 2026-06-04 |

---

## Compaction Group 1 — feat(casehub_block,casehub_delegate)
*5 commits → 1 — 4 spec iteration docs absorbed into implementing feat*

| Commit | Action | Note |
|--------|--------|------|
| `160d68e` feat(casehub_block,casehub_delegate): add Layer 3 lifecycle MCP tools | ✅ KEEP | *(message adequate — unchanged)* |
| `047fc64` docs(spec): Phase 2 skills + casehub_block + DONE dispatch design | 🔽 SQUASH ↑ | Initial spec draft — pre-implementation planning doc |
| `36c52e0` docs(spec): Phase 2 skills + casehub_block + DONE dispatch design | 🔽 SQUASH ↑ | **Identical subject to 047fc64** — duplicate spec iteration |
| `dca852f` docs(spec): address second review round | 🔽 SQUASH ↑ | Spec review iteration |
| `53389c2` docs(spec): final pre-implementation fixes | 🔽 SQUASH ↑ | Spec review iteration |

> **Result:** 1 commit. KEEP message unchanged — the four docs(spec) commits are pre-implementation planning docs that belong with the implementing feat.

**Note:** 047fc64 and 36c52e0 have identical subject lines — clear duplicate spec iteration commits. Both absorbed.

---

## AFTER — what `git log --oneline` will show

```
5  commits

b2dc9eb  docs(arc42): Epic 8 Phase 2 — MCP tool surface, DONE dispatch, lifecycle skills
d29e77b  fix(evaluate): dispatch DONE (not STATUS) on autonomous path via Qhorus-native query
d5e25f1  docs(ActionRiskClassifier): confirm contract identical to engine#402 as of 2026-06-04
cec6ea6  feat(skills): Layer 3 lifecycle SKILL.md — reject, block, delegate
160d68e  feat(casehub_block,casehub_delegate): add Layer 3 lifecycle MCP tools
```
