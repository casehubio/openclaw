# casehub-openclaw — Epic Log

Structured record of integration milestones, optimised for LLM consumption.

**Note:** casehub-openclaw is an integration tier module, not a tutorial application harness.
It does not have tutorial layers in the casehub-work / casehub-qhorus / casehub-engine adoption
sense. Instead, this log tracks epics and milestones in the integration build-out — one entry per
epic, written when the epic closes. Each entry is the raw material needed to understand what was
built and why.

Cross-references:
- Research spec: `../parent/docs/specs/2026-05-25-openclaw-casehub-integration.md`
- Integration spec: `docs/specs/openclaw-integration.md`
- Skill pack spec: `docs/specs/openclaw-skill-pack.md`
- Platform deep-dives: `../parent/docs/repos/casehub-openclaw.md`

---

## Epic 1 — Scaffold

**Status:** In progress
**Issue:** casehubio/openclaw#1
**What it establishes:**
- Maven multi-module project: core/, casehub/, app/, python/
- CLAUDE.md, LAYER-LOG.md, .githooks/pre-push, .github/workflows/publish.yml
- Directory structure with src/main/java, src/main/resources, src/test/java stubs
- Registered in: casehub-parent BOM, full-stack-build.yml, incremental-full-stack-build.yml,
  build-all.sh, dashboard.yml, docs/PLATFORM.md, docs/repos/casehub-openclaw.md
- Workspace setup at /Users/mdproctor/claude/public/casehub/openclaw/

**No code in this epic** — scaffold only.

---

## Epic 2 — OpenClaw Hook API Client

**Status:** Complete
**Issue:** casehubio/openclaw#2
**What was built (core/ module):**

`OpenClawHookClient` (`@ApplicationScoped`) — session registry and invocation service.
- In-memory `ConcurrentHashMap<String, OpenClawSession>` keyed by agentId; last-write-wins
  per agentId (known limitation: concurrent same-agentId workers not supported; upstream fix
  requires workerId in WorkResult — engine enhancement tracked separately)
- `registerSession(agentId, sessionKey, webhookUrl)` / `deregisterSession` / `findSession`
- `invoke(agentId, message, model, timeoutSeconds)` — session lookup, model/timeout defaulting,
  `AgentInvocationRequest.forWebhook()` factory (enforces deliver=webhook), catch
  `WebApplicationException` (Quarkus REST Client throws on 5xx, does not return Response),
  `Response.close()` in finally
- `wake(agentId, message)` — no session lookup required, same error handling pattern

`OpenClawGatewayClient` — `@RegisterRestClient(configKey = "openclaw-gateway")` MicroProfile
REST Client. `@RegisterProvider(BearerTokenRequestFilter.class)` for bearer auth — the only
pattern that reliably applies the filter via CDI (RestClientBuilder does not honour it).

`AgentInvocationRequest` — record with `forWebhook()` package-private static factory.
`sessionName` and `wakeMode` are nullable with `@JsonInclude(NON_NULL)` — omitted from JSON
when null. Session name maps to OpenClaw Python SDK's `session_name` (assumed camelCase for
HTTP API — to verify against live API before casehub/ SPI work).

Tests: 12 pure unit tests (Mockito, no CDI) + 5 `@QuarkusTest` WireMock integration tests
(dynamic port via `QuarkusTestResourceLifecycleManager`). 17/17 green.

**Open questions deferred to later epics:**
- `sessionName` vs `session_name` JSON field name — verify against OpenClaw HTTP API
- `wakeMode` values for direct-call pattern — verify against OpenClaw API docs
- `/hooks/wake` body schema — assumed `{agentId, message}`
- Concurrent same-agentId workers — tracked in casehubio/engine (upstream fix)

---

## Epic 3 — ChannelContextWindow Service

**Status:** Pending
**Issue:** casehubio/openclaw#3 (to be created)
**Planned scope:**
- `MessageObserver` SPI implementation — passive ring buffer population
- Per-channel ring buffer (configurable size + TTL)
- REST endpoint: GET /channel-context/{agentId}?since={sequenceNumber}
- Per-agent last-sequenceNumber tracking
- Overflow and TTL signalling (never silent empty)
- Flyway migration for ring buffer state persistence (named datasource: openclaw)

---

## Epic 4 — CaseHub SPI Implementations

**Status:** Pending
**Issue:** casehubio/openclaw#4 (to be created)
**Planned scope:**
- `WorkerProvisioner` — provisions OpenClaw instances via /hooks/agent on demand
- `ChannelBackend` — bidirectional bridge: Qhorus→OpenClaw and OpenClaw→Qhorus
- `CaseChannelProvider` — creates Qhorus channels per case/purpose
- `WorkerStatusListener` — maps OpenClaw session state to CaseHub worker states
- `MessageObserver` — feeds ChannelContextWindow (already in Epic 3)
- Pluggable context engine (`kind: context-engine`) stub

---

## Epic 5 — Python SDK Component

**Status:** Pending
**Issue:** casehubio/openclaw#5 (to be created)
**Planned scope:**
- `python/src/casehub_openclaw/context_hook.py` — before_prompt_build hook
- `python/src/casehub_openclaw/channel_client.py` — ChannelContextWindow REST client
- appendSystemContext injection (compaction-safe)
- Overflow and TTL notice injection
- pyproject.toml package definition (casehub-openclaw on PyPI)
- README.md with installation and OpenClaw plugin registration instructions

---

## Epic 6 — Bidirectional Wiring

**Status:** Pending
**Issue:** casehubio/openclaw#6 (to be created)
**Planned scope:**
- End-to-end: COMMAND on Qhorus work channel → ChannelBackend.post() → /hooks/agent → LLM → DONE/DECLINE delivered back via deliver:webhook
- Speech act classification: skill instruction prefix approach (Phase 1)
- Integration test covering full round-trip
- Oversight channel gate: ActionRiskClassifier → oversight channel → OpenClaw delivers to messaging → human RESPONSE → workflow continues

---

## Epic 7 — casehub Skill Pack

**Status:** Pending
**Issue:** casehubio/openclaw#7 (to be created)
**Planned scope:**
- Seven OpenClaw skills published to ClawHub registry
- casehub-workitem, casehub-case, casehub-queue, casehub-status, casehub-commit, casehub-done, casehub-context
- Each skill: SKILL.md with YAML frontmatter + instruction block
- Skill pack README for ClawHub listing

---

## Epic 8 — Speech Act Classification

**Status:** Pending
**Issue:** casehubio/openclaw#8 (to be created)
**Planned scope:**
- Skill instruction prefix discipline (Phase 1: [STATUS], [DONE], [EVENT] etc.)
- Structured JSON output format (Phase 2: {"type": "STATUS", "content": "..."})
- Adapter logic in ChannelBackend to parse and route correctly
- Integration tests asserting correct speech act type assignment per skill output
