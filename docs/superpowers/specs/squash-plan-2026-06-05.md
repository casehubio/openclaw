# Squash Plan — issue-10-c8-speech-act → main
**Range:** origin/main..HEAD  **Date:** 2026-06-05  **Commits:** 12 → 6

---

## Already Clean — 0 commits
All commits are action targets.

---

## Group 1 — Spec design doc (4 → 1)

| Commit | Action | Curated result |
|--------|--------|----------------|
| `8b5a118` docs(spec): C8 speech act classification Phase 2+3 design | ✅ KEEP | *(message adequate — unchanged)* |
| `afba91b` docs(spec): revise C8 speech act design — address review findings | 🔽 SQUASH ↑ | *(absorbed — 3 spec revision iterations; KEEP message represents final design)* |
| `e0fec41` docs(spec): second revision C8 speech act design — second review findings | 🔽 SQUASH ↑ | *(absorbed — same file, iteration 2 of 3)* |
| `84f7af4` docs(spec): third revision C8 — null content, strict JSON parser, test label accuracy | 🔽 SQUASH ↑ | *(absorbed — same file, iteration 3 of 3; Pattern 18: near-same-file revisions)* |

> **Result:** 1 commit.

---

## Group 2 — DetectionTier + SpeechActResult data types (1 → 1)

✅ KEEP `c0d891a` feat(casehub): add DetectionTier enum and SpeechActResult record
> Standalone — distinct capability; no file overlap with adjacent commits.

> **Result:** 1 commit.

---

## Group 3 — SpeechActDetection utility (1 → 1)

✅ KEEP `3ce7134` feat(casehub): add SpeechActDetection — JSON + prefix classification utility
> Standalone — 278 lines, distinct capability.

> **Result:** 1 commit.

---

## Group 4 — SPI changes (1 → 1)

✅ KEEP `fd1e9d3` refactor(casehub): SpeechActClassifier returns SpeechActResult; drop SpeechActContext.actionType
> Standalone — refactor ≥ 20 lines, breaking SPI change.

> **Result:** 1 commit.

---

## Group 5 — DefaultSpeechActClassifier (1 → 1)

✅ KEEP `3f1a743` feat(casehub): DefaultSpeechActClassifier Phase 2+3 — JSON+prefix detection, STATUS fallback
> Standalone — feat.

> **Result:** 1 commit.

---

## Group 6 — OversightGateService + skills + javadoc (3 → 1)
**Final message:** `feat(casehub): OversightGateService Phase 2+3 — stripped content, raw audit; casehub-global case step protocol — Refs #10`

| Commit | Action | Curated result |
|--------|--------|----------------|
| `8d5e852` feat(casehub): OversightGateService Phase 2+3 — stripped content, raw audit, SpeechActResult | ✅ KEEP | *(see Final message above)* |
| `4a65943` docs(skills): add case step response protocol to casehub-global | 🔀 MERGE ↑ | *(Pattern 10: docs immediately following feat with same issue #10; both Refs #10; adds significant user-facing protocol to SKILL.md)* |
| `88e1343` docs(casehub): ActionRiskClassifier javadoc — PlannedAction.description is stripped content | 🔽 SQUASH ↑ | *(Pattern 8: Javadoc align; 7 lines, absorbed)* |

> **Result:** 1 commit.

---

## Group 7 — ADR-0003 (1 → 1)

✅ KEEP `707bcb6` adr: 0003 speech act fallback on unrecognised output
> ADR always standalone per policy.

> **Result:** 1 commit.

---

## AFTER — what `git log --oneline` will show

  12  commits (original)
  - 6  absorbed by squash/merge
  ──────────────────────────────
   6  commits — no content lost

Sample (most recent first — simulated):
  707bcb6  adr: 0003 speech act fallback on unrecognised output
  8d5e852  feat(casehub): OversightGateService Phase 2+3 — stripped content, raw audit; casehub-global case step protocol — Refs #10
  3f1a743  feat(casehub): DefaultSpeechActClassifier Phase 2+3 — JSON+prefix detection, STATUS fallback
  fd1e9d3  refactor(casehub): SpeechActClassifier returns SpeechActResult; drop SpeechActContext.actionType
  3ce7134  feat(casehub): add SpeechActDetection — JSON + prefix classification utility
  c0d891a  feat(casehub): add DetectionTier enum and SpeechActResult record
  8b5a118  docs(spec): C8 speech act classification Phase 2+3 design
