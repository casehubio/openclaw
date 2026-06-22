# Squash Plan — 2026-06-07

Range: `upstream/main..HEAD`  
Working branch: `squash/wip-issue-20-20260607-075522`  
31 commits → 18 commits (13 absorbed)

---

## Already Clean — 18 commits survive as KEEP (after merges)

## Action Groups

---

### Group 1 — Layer 3 MCP tools (Layer 3 lifecycle)
*2 commits → 1*

| Commit | Action | Note |
|--------|--------|------|
| `cc790fc` feat(casehub_block,casehub_delegate): add Layer 3 lifecycle MCP tools | ✅ KEEP | |
| `ee8e7bc` docs(ActionRiskClassifier): confirm contract identical to engine#402 as of 2026-06-04 | 🔽 SQUASH ↑ | reordered — docs follow-on annotation absorbed into feat |

---

### Group 2 — Layer 3 SKILL.md
*1 commit — clean*

✅ KEEP `3a624bf` feat(skills): Layer 3 lifecycle SKILL.md — reject, block, delegate

---

### Group 3 — fix(evaluate): dispatch DONE
*1 commit — clean*

✅ KEEP `98e9132` fix(evaluate): dispatch DONE (not STATUS) on autonomous path via Qhorus-native query

---

### Group 4 — ARC42 Epic 8 Phase 2 (Layer 3 docs)
*2 commits → 1*

| Commit | Action | Note |
|--------|--------|------|
| `db27042` docs(arc42): Epic 8 Phase 2 — MCP tool surface, DONE dispatch, lifecycle skills | ✅ KEEP | |
| `09e024d` docs: sync ARC42STORIES.MD — stale scan at session wrap | 🔽 SQUASH ↑ | reordered — earlier session-wrap ARC42 sync absorbed into the substantive arc42 update |

---

### Group 5 — CLAUDE.md Layer 3 update
*1 commit — clean*

✅ KEEP `b10e665` docs(claude-md): add casehub_block, casehub_delegate tools and Layer 3 lifecycle skills

---

### Group 6 — C8 spec
*1 commit — clean*

✅ KEEP `703ef93` docs(spec): C8 speech act classification Phase 2+3 design

---

### Group 7 — SpeechActResult types + refactor (MERGE)
*2 commits → 1*  
**Final message:** `feat(casehub): introduce SpeechActResult record and DetectionTier enum; refactor SpeechActClassifier to return result type`

| Commit | Action | Note |
|--------|--------|------|
| `13783c5` feat(casehub): add DetectionTier enum and SpeechActResult record | ✅ KEEP | *(see Final message above)* |
| `82eeafd` refactor(casehub): SpeechActClassifier returns SpeechActResult; drop SpeechActContext.actionType | 🔀 MERGE ↑ | unified — record introduction and its adoption in the SPI are the same change |

---

### Group 8 — SpeechActDetection utility
*1 commit — clean*

✅ KEEP `1e3ced9` feat(casehub): add SpeechActDetection — JSON + prefix classification utility

---

### Group 9 — DefaultSpeechActClassifier Phase 2+3
*1 commit — clean*

✅ KEEP `d0be768` feat(casehub): DefaultSpeechActClassifier Phase 2+3 — JSON+prefix detection, STATUS fallback

---

### Group 10 — OversightGateService Phase 2+3
*2 commits → 1*

| Commit | Action | Note |
|--------|--------|------|
| `49f829e` feat(casehub): OversightGateService Phase 2+3 — stripped content, raw audit; casehub-global case step protocol | ✅ KEEP | |
| `384a50c` chore: add squash plan for issue-10-c8-speech-act | 🔽 SQUASH ↑ | methodology noise (squash plan file in docs/superpowers/specs/) absorbed |

---

### Group 11 — ADR 0003
*1 commit — clean*

✅ KEEP `15c0f1f` adr: 0003 speech act fallback on unrecognised output

---

### Group 12 — CLAUDE.md Phase 2+3 update
*1 commit — clean*

✅ KEEP `5a60962` docs(claude-md): update SpeechActClassifier — Phase 2+3 shipped, add SpeechActDetection/DetectionTier/SpeechActResult

---

### Group 13 — ARC42 C8 complete
*1 commit — clean*

✅ KEEP `0e35191` docs: sync ARC42STORIES.MD — C8 complete, stale scan at session wrap

---

### Group 14 — S-items spec (5 revisions squashed)
*6 commits → 1*

| Commit | Action | Note |
|--------|--------|------|
| `6be57f7` docs(spec): 2026-06-06 s-items — #20 crash-safe channelId, #22 atomicity test, #25 oversight deniedTypes | ✅ KEEP | |
| `b4f53a9` docs(spec): revise s-items spec | 🔽 SQUASH ↑ | spec iteration |
| `55d0a12` docs(spec): second revision | 🔽 SQUASH ↑ | spec iteration |
| `2dce71b` docs(spec): third revision | 🔽 SQUASH ↑ | spec iteration |
| `b3b9e59` docs(spec): fourth revision | 🔽 SQUASH ↑ | spec iteration |
| `46afa5e` docs(spec): fifth revision | 🔽 SQUASH ↑ | spec iteration |

---

### Group 15 — #25 oversight channel deniedTypes
*2 commits → 1*

| Commit | Action | Note |
|--------|--------|------|
| `226b038` feat(casehub): oversight channel deniedTypes=EVENT via ChannelSpec record (#25) | ✅ KEEP | Closes #25 |
| `a13de0f` docs: sync ARC42STORIES.MD — oversight channel deniedTypes resolved (#25, claudony#142) | 🔽 SQUASH ↑ | docs follow-on for #25 |

---

### Group 16 — #20 drop channelMap
*3 commits → 1*

| Commit | Action | Note |
|--------|--------|------|
| `94dd113` feat(app): drop channelMap from CommitmentTools — crash-safe channelId via Commitment entity (#20) | ✅ KEEP | Closes #20 |
| `9503c77` test(app): add error code assertion to delegate post-transfer guard | 🔽 SQUASH ↑ | test fixup |
| `c1b098c` test(app): fix review findings — error code assertion, channel-only negative/terminal tests | 🔽 SQUASH ↑ | test fixup |

---

### Group 17 — #22 CDI wiring test
*2 commits → 1*

| Commit | Action | Note |
|--------|--------|------|
| `f1bc934` test(app): OversightGateDispatcherCdiTest — CDI wiring + fail-open coverage (#22) | ✅ KEEP | Closes #22 |
| `323eadb` test(app): clear InMemory stores in @BeforeEach; add ordering comment | 🔽 SQUASH ↑ | test fixup |

---

### Group 18 — Protocol PP-20260607-84b26d
*1 commit — clean*

✅ KEEP `4719633` protocol(PP-20260607-84b26d): mcp-tool-no-instance-cache

---

## AFTER

```
31 commits (original)
-13 absorbed by squash
─────────────────────
18 commits
```

Sample post-squash (most recent first):
- `4719633` protocol(PP-20260607-84b26d): mcp-tool-no-instance-cache
- `f1bc934` test(app): OversightGateDispatcherCdiTest — CDI wiring + fail-open coverage (#22)
- `94dd113` feat(app): drop channelMap from CommitmentTools — crash-safe channelId via Commitment entity (#20)
- `226b038` feat(casehub): oversight channel deniedTypes=EVENT via ChannelSpec record (#25)
- `6be57f7` docs(spec): 2026-06-06 s-items
- `0e35191` docs: sync ARC42STORIES.MD — C8 complete
- `5a60962` docs(claude-md): update SpeechActClassifier — Phase 2+3 shipped
- `15c0f1f` adr: 0003 speech act fallback on unrecognised output
- `49f829e` feat(casehub): OversightGateService Phase 2+3
- `d0be768` feat(casehub): DefaultSpeechActClassifier Phase 2+3
- `1e3ced9` feat(casehub): add SpeechActDetection
- `13783c5` feat(casehub): introduce SpeechActResult record and DetectionTier enum; refactor SpeechActClassifier
- `703ef93` docs(spec): C8 speech act classification Phase 2+3 design
- `b10e665` docs(claude-md): add casehub_block, casehub_delegate tools
- `db27042` docs(arc42): Epic 8 Phase 2
- `98e9132` fix(evaluate): dispatch DONE (not STATUS)
- `3a624bf` feat(skills): Layer 3 lifecycle SKILL.md
- `cc790fc` feat(casehub_block,casehub_delegate): add Layer 3 lifecycle MCP tools
```
