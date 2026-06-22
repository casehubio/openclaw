# Squash Plan — 2026-06-15 — main (issue-32-layout-gate-tenancy)

Range: `origin/main..HEAD` — 12 commits → 4 commits

---

## Group 1 — #29 docs follow-ons (2 commits → 1)

**Final message:** `docs: add delivery-webhook-cross-tenant-reads protocol to CLAUDE.md; sync openclaw-integration.md — Refs #29`

| Commit | Action | Curated result |
|--------|--------|----------------|
| `2a02902` docs: add PP-20260612-520281 to Key Protocols table — delivery webhook cross-tenant reads | ✅ KEEP | *(see Final message above)* |
| `1be28e3` docs: sync openclaw-integration.md — multi-tenancy delivery webhook and ChannelContextWindow tenant scoping | 🔽 SQUASH ↑ | *(absorbed — same session doc sweep for #29, both are docs from the same delivery-webhook scope)* |

> **Result:** 1 commit.

---

## Group 2 — #32 layout extraction (4 commits → 1)

**Final message:** `feat(casehub): extract OpenClawNormativeLayout — single source of truth for 3-channel layout — Closes #32`

| Commit | Action | Curated result |
|--------|--------|----------------|
| `010f5cc` feat(casehub): add OpenClawNormativeLayout — single source of truth for 3-channel layout — Refs #32 | ✅ KEEP | *(see Final message above — Refs → Closes)* |
| `7610b73` refactor(casehub): OpenClawCaseChannelProvider uses OpenClawNormativeLayout — Refs #32 | 🔀 MERGE ↑ | *(unified — provider migration is part of the extraction, not a separate capability)* |
| `fb87eb4` refactor(casehub): ReactiveOpenClawCaseChannelProvider uses OpenClawNormativeLayout — Refs #32 | 🔀 MERGE ↑ | *(unified — reactive provider migration, same scope as blocking provider)* |
| `e06c0ef` protocol(PP-20260615-11b9d2): normative-layout-single-source — Refs #32 | 🔽 SQUASH ↑ | *(absorbed — protocol follows from the extraction; does not stand alone)* |

> **Result:** 1 commit.

---

## Group 3 — #34 gate recovery (1 commit, already clean)

✅ KEEP `1f7e8d7` fix(casehub): recover tenancyId from CrossTenantChannelStore for pre-#29 gates in fulfill() — Refs #34

**Final message:** `fix(casehub): recover tenancyId from CrossTenantChannelStore for pre-#29 gates in fulfill() — Closes #34`
*(Refs → Closes — branch covers this issue completely)*

> **Result:** 1 commit.

---

## Group 4 — #33 AgentKey removal (5 commits → 1)

**Final message:** `refactor: remove AgentKey — ChannelContextWindowService uses plain agentId key — Closes #33`

| Commit | Action | Curated result |
|--------|--------|----------------|
| `c9f6b75` refactor(core): remove AgentKey — ChannelContextWindowService uses plain agentId key — Refs #33 | ✅ KEEP | *(see Final message above — Refs → Closes)* |
| `f30cc7e` refactor(casehub): update provisioners and status listener to 1-arg unbindAgent/2-arg bindAgent — Refs #33 | 🔀 MERGE ↑ | *(unified — caller updates are the completion of the API change, not a separate refactor)* |
| `6f17422` refactor(app): ChannelContextWindowResource and CasehubMcpResources remove CurrentPrincipal — service resolves tenancyId internally — Refs #33 | 🔀 MERGE ↑ | *(unified — resource update completes the API change; new endpoint behaviour documented in body)* |
| `9dc0590` docs: update CLAUDE.md — ChannelContextWindowService API, normative layout protocol — Refs #33 #32 | 🔽 SQUASH ↑ | *(absorbed — docs follow-on; CLAUDE.md update reflects changes already described by Groups 2 and 4)* |
| `3fb34ec` docs: sync openclaw-integration.md — ChannelContextWindow tenancyId-free endpoint, remove stale AgentKey reference — Refs #33 | 🔽 SQUASH ↑ | *(absorbed — spec sync for the same change; integration spec update is part of the same refactor)* |

> **Result:** 1 commit.

---

## AFTER — what `git log --oneline` will show (origin/main base)

```
12 commits (original)
 -8 absorbed by squash/merge
──────────────────────────────────
 4 commits — no content lost

Sample (most recent first, after squash):
  <sha>  refactor: remove AgentKey — ChannelContextWindowService uses plain agentId key — Closes #33
  <sha>  fix(casehub): recover tenancyId from CrossTenantChannelStore for pre-#29 gates in fulfill() — Closes #34
  <sha>  feat(casehub): extract OpenClawNormativeLayout — single source of truth for 3-channel layout — Closes #32
  <sha>  docs: add delivery-webhook-cross-tenant-reads protocol to CLAUDE.md; sync openclaw-integration.md — Refs #29
```
