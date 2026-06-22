# Squash Plan — upstream/main..HEAD — 2026-05-29

**Range:** `upstream/main..HEAD` (39 commits)
**Working branch:** `squash/wip-main-20260529-230759`

## Summary

| Before | After | Change |
|--------|-------|--------|
| 39 commits | 16 commits | -2 dropped (empty), -21 absorbed |

---

## Already Clean — 0 commits (all have action)

---

## Action Groups

### Group 1 — Epic 1: OpenClaw Hook API Client
*15 commits → 2*

**1a — Design spec**
✅ KEEP `b4ffc4c` docs(specs): add OpenClaw hook API client design spec
> Result: unchanged

**1b — Core implementation**
**Final message:** `feat(core): OpenClawHookClient — REST client, gateway filter, session registry, WireMock tests`

| Commit | Action |
|--------|--------|
| `355a8bf` feat(core): implement OpenClawHookClient with session registry and invocation | ✅ KEEP *(see Final message)* |
| `d7980b7` feat(core): add OpenClawGatewayClient @RegisterRestClient and BearerTokenRequestFilter | 🔽 SQUASH ↑ |
| `ceed46a` feat(core): add AgentInvocationRequest and AgentWakeRequest records | 🔽 SQUASH ↑ |
| `f2f76e1` feat(core): add OpenClawClientConfig @ConfigMapping | 🔽 SQUASH ↑ |
| `dcbacc9` feat(core): add OpenClawInvocationException and OpenClawSession record | 🔽 SQUASH ↑ |
| `a63d15b` build(core): add WireMock + Mockito test deps, Quarkus generate-code-tests goal | 🔽 SQUASH ↑ |
| `4a1e261` build: move wiremock version into root dependencyManagement | 🔽 SQUASH ↑ |
| `24a25b7` refactor(core): document null wakeMode in forWebhook() call | 🔽 SQUASH ↑ |
| `94e635b` test(core): add WireMock integration test for OpenClawGatewayClient | 🔽 SQUASH ↑ |
| `da4ca64` fix(core): null-guard WebApplicationException.getResponse() in error paths | 🔽 SQUASH ↑ |
| `6551313` build: fix full-module build — qhorus datasource and casehub scaffold scoping | 🔽 SQUASH ↑ |
| `c2db602` fix(app): correct gateway.bearer-token key and add REST client URL bridge | 🔽 SQUASH ↑ |
| `1585069` docs: update LAYER-LOG.md — Epic 2 complete | 🔽 SQUASH ↑ |

> **Result:** 2 commits

**1c — Epic 1 branch marker**
| `26982e5` chore: branch closed | ❌ DROP *(zero file changes confirmed)* |

---

### Group 2 — Epic 3: ChannelContextWindow
*12 commits → 3*

**2a — Design spec**
**Final message:** `docs(specs): ChannelContextWindow service design spec`

| Commit | Action |
|--------|--------|
| `4400611` docs(specs): ChannelContextWindow service design spec | ✅ KEEP *(see Final message)* |
| `bddc9b6` docs(specs): address 12 design review findings on ChannelContextWindow | 🔽 SQUASH ↑ *(review application — absorbed)* |

**2b — Core implementation**
**Final message:** `feat(core): ChannelContextWindow — ring buffer, service, observer, REST endpoint, eviction scheduler, tests`

| Commit | Action |
|--------|--------|
| `3a90d64` feat(core): ChannelContextWindowService — in-memory ring buffer with associate/add/query | ✅ KEEP *(see Final message)* |
| `c6f2064` feat(core): ChannelRingBuffer — per-channel bounded ring buffer with TTL and overflow tracking | 🔽 SQUASH ↑ |
| `9499b15` feat(core): ContextMessage and WindowContent records for ChannelContextWindow | 🔽 SQUASH ↑ |
| `161bf34` chore(app): add quarkus-scheduler + mockito deps, context-window config | 🔽 SQUASH ↑ |
| `73b6506` feat(casehub): ChannelContextWindowObserver — MessageObserver SPI feeding ring buffer | 🔽 SQUASH ↑ |
| `a72c1dd` feat(app): ChannelContextWindowResource REST endpoint + EvictionScheduler | 🔽 SQUASH ↑ |
| `9d80991` test(core): strengthen restart detection and noAssociation cursor invariant tests | 🔽 SQUASH ↑ |
| `750ddbc` fix(core): make agentChannels Set immutable to eliminate associate/query race | 🔽 SQUASH ↑ |
| `561ffeb` refactor(core): address code quality follow-ups from Epic 2 review (#9) | 🔽 SQUASH ↑ |

**2c — CLAUDE.md sync (project artifact — keep standalone)**
✅ KEEP `e52af98` docs: sync CLAUDE.md for Epic 3 — ChannelContextWindow implementation

**2d — Epic 3 branch marker**
| `5472692` chore: branch closed | ❌ DROP *(zero file changes confirmed)* |

> **Result:** 3 commits

---

### Group 3 — Epic 4: CaseHub SPI Implementations
*11 commits → 7*

**3a — API refactor (keep standalone — major API change)**
✅ KEEP `307ba2b` refactor(core): replace associate() with bindAgent/bindChannel/unbindAgent

**3b — Agent registry + config**
**Final message:** `feat(casehub): OpenClawAgentRegistry + CasehubConfig — routing maps and capability config`

| Commit | Action |
|--------|--------|
| `3de8c66` feat(casehub): OpenClawAgentRegistry — caseId↔agentId↔sessionKey routing maps | ✅ KEEP *(see Final message)* |
| `f747485` feat(casehub): OpenClawCasehubConfig — @ConfigMapping for agent capability mapping | 🔽 SQUASH ↑ |

**3c–3f — SPI implementations (each distinct, all KEEP)**
✅ KEEP `d31e65d` feat(casehub): OpenClawWorkerProvisioner — WorkerProvisioner SPI implementation
✅ KEEP `11a3feb` feat(casehub): OpenClawCaseChannelProvider — CaseChannelProvider SPI implementation
✅ KEEP `ea8a8b4` feat(casehub): OpenClawChannelBackend — ChannelBackend SPI + self-registration
✅ KEEP `941a480` feat(casehub): OpenClawWorkerStatusListener — WorkerStatusListener SPI implementation

**3g — Delivery + wiring + spec**
**Final message:** `feat(app): OpenClawDeliveryResource — delivery webhook, CDI wiring fixes, design spec`

| Commit | Action |
|--------|--------|
| `e9dfeec` feat(app): OpenClawDeliveryResource + CDI wiring fixes | ✅ KEEP *(see Final message)* |
| `ab7e2aa` build(casehub): remove provided scope from casehub-engine and casehub-qhorus | 🔽 SQUASH ↑ |
| `c6134ed` fix: address code review findings — non-throwing backend, overwrite warning, tests | 🔽 SQUASH ↑ |
| `df7a213` docs: promote Epic 4 design spec from workspace | 🔽 SQUASH ↑ *(docs/specs/ is project artifact — absorbed into delivery commit)* |

> **Result:** 7 commits

---

### Group 4 — Epic 5: TypeScript Plugin + Python SDK
*13 commits → 4*

**4a — Design spec**
**Final message:** `docs(specs): Epic 5 design — TypeScript plugin + Python client library`

| Commit | Action |
|--------|--------|
| `e8ea23a` docs(specs): Epic 5 design — TypeScript plugin + Python client library | ✅ KEEP *(see Final message)* |
| `5bbe276` docs(specs): apply Epic 5 spec review — fix all blockers and significant gaps | 🔽 SQUASH ↑ *(review application — absorbed)* |

**4b — TypeScript plugin**
**Final message:** `feat(plugin): OpenClaw TypeScript plugin — before_prompt_build hook, cursor management, formatters, HTTP client`

| Commit | Action |
|--------|--------|
| `7cd7dea` feat(plugin): implement ChannelContextPlugin with before_prompt_build hook | ✅ KEEP *(see Final message)* |
| `3d9eae0` chore(plugin): scaffold TypeScript plugin project structure | 🔽 SQUASH ↑ |
| `6d124c6` feat(plugin): add TypeScript type interfaces for ChannelContextWindow and OpenClaw Plugin API | 🔽 SQUASH ↑ |
| `13173d9` feat(plugin): implement formatMessages and formatIdle with tests | 🔽 SQUASH ↑ |
| `99946e8` feat(plugin): implement ChannelClient with URL encoding and timeout support | 🔽 SQUASH ↑ |
| `4eb63d0` fix(plugin): add .gitignore; remove committed node_modules/dist; fix cast and missing test | 🔽 SQUASH ↑ |
| `9d2dff4` docs(python): update README; add files field to plugin package.json | 🔽 SQUASH ↑ |

**4c — Python client library**
**Final message:** `feat(python): Python client library — ChannelClient, Pydantic models, URL encoding`

| Commit | Action |
|--------|--------|
| `c1bb714` feat(python): implement ChannelClient with URL encoding | ✅ KEEP *(see Final message)* |
| `5c073fe` feat(python): Pydantic v2 models for WindowContent and ContextMessage | 🔽 SQUASH ↑ |
| `6f331de` chore(python): remove context_hook.py stub; update dev deps (respx, build) | 🔽 SQUASH ↑ |

**4d — ADR (keep standalone)**
✅ KEEP `9804ad8` adr: 0001 OpenClaw hook implementation language

> **Result:** 4 commits

---

## AFTER — what `git log --oneline` will show (estimated)

```
39 commits (original)
 -2 dropped (empty branch markers)
-21 absorbed by squash
──────────────────────────────────
16 commits — no content lost
```

Estimated survivors (newest → oldest):
```
adr: 0001 OpenClaw hook implementation language
feat(plugin): OpenClaw TypeScript plugin — before_prompt_build hook...
feat(python): Python client library — ChannelClient, Pydantic models...
docs(specs): Epic 5 design — TypeScript plugin + Python client library
feat(app): OpenClawDeliveryResource — delivery webhook, CDI wiring, design spec
feat(casehub): OpenClawWorkerStatusListener...
feat(casehub): OpenClawChannelBackend...
feat(casehub): OpenClawCaseChannelProvider...
feat(casehub): OpenClawWorkerProvisioner...
feat(casehub): OpenClawAgentRegistry + CasehubConfig...
refactor(core): replace associate() with bindAgent/bindChannel/unbindAgent
docs: sync CLAUDE.md for Epic 3 — ChannelContextWindow implementation
feat(core): ChannelContextWindow — ring buffer, service, observer, REST endpoint...
docs(specs): ChannelContextWindow service design spec
feat(core): OpenClawHookClient — REST client, gateway filter, session registry, WireMock tests
docs(specs): add OpenClaw hook API client design spec
```
