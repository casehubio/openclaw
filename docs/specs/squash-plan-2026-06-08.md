# Squash Plan — issue-28-tool-call-first-completion — 2026-06-08

Range: `upstream/main..HEAD` (8 commits → 2 commits)

---

## Already Clean — 0 commits

All commits are in action groups.

---

## Group 1 — feat(casehub): tool-call-first completion

*Compaction group — 7 commits → 1*
**Final message:** `feat(casehub): tool-call-first completion — remove speech act classification; sync integration spec (#28)`

| Commit | Action | Curated result |
|--------|--------|----------------|
| `408a470` feat(casehub): tool-call-first completion — remove speech act classification (#28) | ✅ KEEP | *(see Final message above)* |
| `120e4cf` docs: sync openclaw-integration.md for tool-call-first completion (#28) | 🔀 MERGE ↑ | *(unified — same issue, immediate follow-on; sync of integration spec belongs with implementation)* |
| `38c249d` docs(spec): editorial fixes — renumber §8 heading and add missing DELETE row | 🔽 SQUASH ↑ | *(absorbed — spec editorial cleanup; no standalone value)* |
| `f591aa4` docs(spec): revise tool-call-first spec — round 4 | 🔽 SQUASH ↑ | *(absorbed — iterative spec revision before implementation)* |
| `c607ca4` docs(spec): revise tool-call-first spec — round 3 | 🔽 SQUASH ↑ | *(absorbed — iterative spec revision before implementation)* |
| `593c942` docs(spec): revise tool-call-first spec — all four review issues addressed | 🔽 SQUASH ↑ | *(absorbed — iterative spec revision before implementation)* |
| `dd3bfea` docs(spec): tool-call-first completion architecture design | 🔽 SQUASH ↑ | *(absorbed — design spec preparatory to feat; per semantic grouping rule absorbed forward into implementing commit)* |

📝 Spec commits (`dd3bfea` through `38c249d`) precede the feat chronologically — all squash forward into the feat as the first KEEP. Body note: `[Plan: tool-call-first completion architecture design]`

> **Result:** 1 commit.

---

## Group 2 — ADR: tool-call-first completion signaling

*Already clean — 1 commit (no action)*

✅ KEEP `e0976c2` adr: 0004 tool-call-first completion signaling; supersede ADR-0003

> **Result:** 1 commit.

---

## AFTER — what `git log --oneline` will show

```
  8  commits (original)
  -6  absorbed by squash/merge
  ─────────────────────────────────
  2  commits — no content lost
```

Sample (post-squash — SHAs estimated):
```
<sha>  adr: 0004 tool-call-first completion signaling; supersede ADR-0003
<sha>  feat(casehub): tool-call-first completion — remove speech act classification; sync integration spec (#28)
```
