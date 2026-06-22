# Squash Plan — 2026-06-12 — main (issue-29-tenancyid-propagation)

Range: `origin/main..HEAD` — 17 commits → 13 commits

## Already Clean — 11 commits (no action needed)

✅ KEEP `ef7e9fb` docs: sync ARC42STORIES.MD — stale scan at session wrap (close #12, #19)
✅ KEEP `d05cae3` feat(core): AgentKey composite key for ChannelContextWindowService
✅ KEEP `c488dc9` feat(casehub): OpenClawAgentRegistry — caseToTenancy map and findTenancyId()
✅ KEEP `3e78937` feat(casehub): GateContext + OversightGateDispatcher tenancyId threading
✅ KEEP `b5bdd85` feat(casehub): OversightGateService — tenancyId in evaluate/openGate/fulfill
✅ KEEP `77d1e77` feat(app): CommitmentTools — inject CurrentPrincipal, pass tenancyId to openGate
✅ KEEP `c1eb61e` feat(app): delivery resource CrossTenant + context window CurrentPrincipal
✅ KEEP `2a65e68` test(app): rewrite CdiTest + cross-tenant isolation test + fix test infrastructure
✅ KEEP `0025729` fix(app): exclude MockCurrentPrincipal from CDI; index platform-testing
✅ KEEP `9b02a0f` protocol(PP-20260612-520281): delivery-webhook-cross-tenant-reads

---

## Group 1 — Spec revisions (5 commits → 1)

**Final message:** `docs: tenancyId propagation design spec (openclaw#29)`

| Commit | Action | Curated result |
|--------|--------|----------------|
| `695a3d9` docs: tenancyId propagation design spec (openclaw#29) | ✅ KEEP | *(see Final message above)* |
| `0025d67` docs: fix spec self-review issues | 🔽 SQUASH ↑ | *(absorbed — docs follow-on, 3 rounds of spec review)* |
| `b09ab16` docs: revise tenancyId propagation spec after code review | 🔽 SQUASH ↑ | *(absorbed)* |
| `9d4d0c0` docs: revise spec — scan extraction, dispatcher test surgery | 🔽 SQUASH ↑ | *(absorbed)* |
| `17f5408` docs: add CommitmentToolsTest surgery callout to spec | 🔽 SQUASH ↑ | *(absorbed)* |

> **Result:** 1 commit.

---

## Group 2 — Provisioner tenancyId + behavioral fix (2 commits → 1)

**Final message:** `feat(casehub): tenancyId in provisioners and status listener — unbindAgent unconditional (Refs #29)`

| Commit | Action | Curated result |
|--------|--------|----------------|
| `193b4a2` feat(casehub): tenancyId in provisioners and status listener — Refs #29 | ✅ KEEP | *(see Final message above)* |
| `9a0803c` fix(casehub): provisioners call unbindAgent unconditionally | 🔀 MERGE ↑ | *(behavioral fix to same code, 4 min gap — part of same provisioner design)* |

> **Result:** 1 commit.

---

## AFTER — `git log --oneline` will show

```
17 commits (original)
- 4 absorbed (docs squash)
- 1 merged (provisioner fix)
──────────────────────────────
13 commits — no content lost
```
