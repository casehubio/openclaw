# casehub-openclaw Workspace

**Name:** casehub-openclaw

**Physical path:** /Users/mdproctor/claude/casehub/openclaw/CLAUDE.md
**Symlinked at:** /Users/mdproctor/claude/public/casehub/openclaw/CLAUDE.md
**Project repo:** /Users/mdproctor/claude/casehub/openclaw
**Workspace:** /Users/mdproctor/claude/public/casehub/openclaw
**Workspace type:** public

## Session Start

Run `add-dir /Users/mdproctor/claude/casehub/openclaw` before any other work.

## Artifact Locations

| Skill | Writes to |
|-------|-----------|
| brainstorming (specs) | `specs/` |
| writing-plans (plans) | `plans/` |
| handover | `HANDOFF.md` |
| idea-log | `IDEAS.md` |
| design-snapshot | `snapshots/` |
| java-update-design / update-primary-doc | `design/JOURNAL.md` (created by `epic`) |
| adr | `adr/` |
| write-blog | `blog/` |

## Structure

- `HANDOFF.md` — session handover (single file, overwritten each session)
- `IDEAS.md` — idea log (single file)
- `specs/` — brainstorming / design specs (superpowers output)
- `plans/` — implementation plans (superpowers output)
- `snapshots/` — design snapshots with INDEX.md (auto-pruned, max 10)
- `adr/` — architecture decision records with INDEX.md
- `blog/` — project diary entries with INDEX.md
- `design/` — epic journal (created by `epic` at branch start)

## Git Discipline

Two git repositories are active in every session:
- **Workspace** (`/Users/mdproctor/claude/public/casehub/openclaw`) — methodology artifacts: handover, blog, specs, plans, ADRs
- **Project repo** (`/Users/mdproctor/claude/casehub/openclaw`) — source code

Before any git operation, run `git rev-parse --show-toplevel` to confirm which repo is currently active. Do not assume — the session may have opened in either. Use `git -C <path>` for explicit repo targeting:
- Source code commits → project repo
- Methodology artifacts → workspace

## Peer Repos — Hard Boundary

**This session owns exactly two repos: the workspace and the project repo.**
Every other casehubio repo is a peer repo with its own Claude session.

Peer repos (never commit or push to these from this session):
- `/Users/mdproctor/claude/casehub/parent` and all paths under it
- `/Users/mdproctor/claude/casehub/engine`
- `/Users/mdproctor/claude/casehub/ledger`
- `/Users/mdproctor/claude/casehub/work`
- `/Users/mdproctor/claude/casehub/qhorus`
- `/Users/mdproctor/claude/casehub/connectors`
- `/Users/mdproctor/claude/casehub/claudony`
- `/Users/mdproctor/claude/casehub/platform`
- `/Users/mdproctor/claude/casehub/life`
- `/Users/mdproctor/claude/casehub/aml`
- `/Users/mdproctor/claude/casehub/clinical`
- `/Users/mdproctor/claude/casehub/devtown`
- Any other sibling directory under `/Users/mdproctor/claude/casehub/`

**When a cross-repo doc change is needed** (e.g. `docs/PLATFORM.md`,
`docs/repos/casehub-openclaw.md` in the parent): file a GitHub issue on
`casehubio/parent` describing the change — never edit or commit directly.

Skills that check this (implementation-doc-sync, work-end, handover) must
read this section before deciding where to commit doc changes.

## Routing

| Artifact   | Destination | Notes |
|------------|-------------|-------|
| adr        | project     | lands in `docs/adr/` — promoted at epic close |
| specs      | project     | lands in `docs/specs/` — promoted at epic close |
| blog       | workspace   | staged here; published to mdproctor.github.io via publish-blog |
| plans      | workspace   | stay in workspace permanently |
| design     | workspace   | epic journal stays in workspace |
| snapshots  | workspace   | stay in workspace permanently |
| handover   | workspace   | |

---

# casehub-openclaw — Claude Code Project Guide

## Platform Context

This repo is one component of the casehubio multi-repo platform. **Before implementing anything — any feature, SPI, data model, or abstraction — run the Platform Coherence Protocol.**

> **Platform docs:** Local paths use `../parent/docs/` as root. If a path doesn't exist, the parent repo isn't cloned locally — fetch from `https://raw.githubusercontent.com/casehubio/parent/main/docs/<path>` instead.

The protocol asks: Does this already exist elsewhere? Is this the right repo for it? Does this create a consolidation opportunity? Is this consistent with how the platform handles the same concern in other repos?

**Platform architecture (fetch before any implementation decision):**
```
../parent/docs/PLATFORM.md
```

**Foundation repo deep-dives** (fetch the relevant ones when your implementation touches their domain):
- casehub-engine: `../parent/docs/repos/casehub-engine.md`
- casehub-ledger: `../parent/docs/repos/casehub-ledger.md`
- casehub-qhorus: `../parent/docs/repos/casehub-qhorus.md`
- casehub-connectors: `../parent/docs/repos/casehub-connectors.md`
- casehub-platform: `../parent/docs/repos/casehub-platform.md`

---

## Project Type

type: java

**Stack:** Java 21 (on Java 26 JVM), Quarkus 3.32.2

**Python component:** `python/` — own `pyproject.toml`; published to PyPI independently. Uses the OpenClaw Python SDK (not Maven). Do not treat `python/` as a Maven module.

---

## What This Project Is

`casehub-openclaw` is the **integration tier** bridging CaseHub and OpenClaw. It occupies the same architectural position as Claudony — implementing CaseHub engine SPIs backed by an external agent runtime — but targets OpenClaw's hook API rather than the Claude CLI.

This is **not** an application layer and **not** a framework. It is the wiring between two systems: CaseHub provides orchestration, accountability, and audit primitives; OpenClaw provides the agent runtime with its hook-based invocation model. casehub-openclaw makes OpenClaw agents first-class CaseHub workers.

### What It Does

**CaseHub SPI implementations (Java — `casehub/` module):**
- `WorkerProvisioner` — provisions OpenClaw agents as CaseHub workers via the hook API
- `ChannelBackend` — delivers Qhorus channel messages to OpenClaw agents
- `CaseChannelProvider` — creates and manages Qhorus-backed channels for OpenClaw agent sessions
- `WorkerStatusListener` — maps OpenClaw agent lifecycle events to CaseHub worker status
- `MessageObserver` — observes Qhorus channel activity and feeds the ChannelContextWindow

**Oversight gate (Java — `casehub/` module):**
- `OversightGateService` — `evaluate()` archives agent webhook output as a non-resolving STATUS message (no correlationId, no commitment state change); `fulfill()` processes human responses to oversight gates (see openclaw#30 for Phase 2 gate wiring via `CommitmentTools.done()`)
- `CaseChannelNames` — package-private utility for case channel name operations shared across the `casehub/` module

**ChannelContextWindow service (`core/` module):**
A short-term, TTL-evicting ring buffer of Qhorus channel activity. Exposed as a REST API. Consumed by the Python SDK hook at agent turn start to inject channel context into the agent's system prompt. Provides best-effort intelligence enrichment — not correctness — so it is allowed to fail open.

**Python SDK component (`python/` directory):**
- `before_prompt_build` hook — fires before each OpenClaw agent turn; calls `GET /channel-context/{agentId}` and injects the window content as `appendSystemContext`
- Channel client — thin HTTP client to the ChannelContextWindow REST API
- Compaction-safe: uses `appendSystemContext` (not context replacement) so Claude's compaction pass preserves it

**Bidirectional channel wiring:** Qhorus channel messages drive OpenClaw agents; OpenClaw agent outputs are posted back to Qhorus channels. The integration is symmetric. Completion signaling is via MCP tool calls (`casehub_done`, `casehub_reject`, etc.); the deliver:webhook path archives agent text as STATUS. Human oversight gate fulfillment (`fulfill()`) remains intact for Phase 2 wiring (openclaw#30).

---

## Architecture

Three Maven modules plus an independent Python component:

```
core/       — OpenClaw hook API client; ChannelContextWindow ring buffer + REST service
casehub/    — CaseHub SPI implementations (WorkerProvisioner, ChannelBackend, etc.)
app/        — Quarkus deployment (MCP endpoint, delivery webhook, ChannelContextWindow REST API, plugin REST API, example demo endpoints)
python/     — before_prompt_build hook + channel client (NOT a Maven module)
plugin/     — TypeScript OpenClaw plugin (before_prompt_build, commitment lifecycle hooks)
skills/     — OpenClaw SKILL.md files (casehub-global, casehub-workitem, casehub-case, casehub-queue, casehub-status, casehub-reject, casehub-block, casehub-delegate)
examples/   — Runnable demo scenarios (multi-agent-dev-team, trading-oversight, incident-response); docker-compose + Python mocks + scripts; NOT a Maven module
```

### Module Detail

**`core/`** owns:
- OpenClaw hook API client: `POST /hooks/agent` with fields `{message, agentId, deliver, to, model, timeoutSeconds}`
- `deliver: webhook` configuration — OpenClaw POSTs the agent result back to a Qhorus channel endpoint
- `ChannelContextWindow` — ring buffer, TTL eviction, global `windowSeq` cursor, overflow signal via `lastEvictionWindowSeq`
- `ChannelContextWindowService` — manages the buffer per agent (`bindAgent(agentId, caseId)`, `bindChannel`, `add`, `query(agentId, since)`, `evictExpired`); uses plain agentId as the map key (consistent with `OpenClawAgentRegistry`); exposed via REST in `app/`
- `ContextMessage` record, `WindowContent` record, `ChannelRingBuffer` (package-private)

**`casehub/`** owns:
- All CaseHub SPI implementations
- `ChannelContextWindowObserver` — implements `MessageObserver` SPI; synchronously receives every Qhorus dispatch and feeds the ring buffer; must never throw to Qhorus
- `OversightGateService` — `evaluate()` archives webhook text as archival STATUS; `fulfill()` processes human oversight gate responses (openclaw#30 wires gate entry via `CommitmentTools.done()`)
- `CaseChannelNames` — package-private channel name utility

**`app/`** owns:
- `POST /openclaw/delivery/channel/{channelId}` — receives `deliver:webhook` callbacks from OpenClaw; delegates to `OversightGateService.evaluate()` (always 200)
- `POST /openclaw/delivery/oversight/{gateId}` — receives human oversight responses from OpenClaw; delegates to `OversightGateService.fulfill()` (always 200)
- `GET /channel-context/{agentId}?since={windowSeq}` — ChannelContextWindow REST API (always 200; `since` defaults to 0)
- `EvictionScheduler` — `@Scheduled` bean that calls `service.evictExpired()` at the TTL interval
- `POST /mcp` — Quarkus MCP endpoint (`quarkus-mcp-server-http:1.11.1`); exposes commitment tools and resources via MCPorter streamable-HTTP transport
  - Tools: `casehub_commit`, `casehub_done`, `casehub_reject`, `casehub_checkpoint`, `casehub_escalate`, `casehub_block`, `casehub_delegate`, `casehub_create_workitem`, `casehub_queue`, `casehub_status`
  - Resources: `casehub://agent/{agentId}/commitments`, `casehub://channel/{agentId}/recent`
- `POST /openclaw/plugin/commit` — plugin auto-commit REST endpoint (called by TypeScript plugin `before_tool_call` hook; not for LLM use)
- `POST /openclaw/plugin/done` — plugin auto-done REST endpoint (called by `agent_end` hook)
- `GET /openclaw/plugin/commitments/{agentId}` — open commitment query for `session_start` injection
- `POST /example/{exampleId}/start` — demo scenario orchestrator (`@Blocking`; inert when `casehub.example.enabled=false`); lives in `app/example/` subpackage with `DemoGateClassifier`, `ExampleSetup`, `ExamplePoller`

**`python/`** owns:
- `before_prompt_build` hook implementation
- HTTP client to `GET /channel-context/{agentId}`
- Published to PyPI independently of the Maven build

**`plugin/`** owns (TypeScript — not a Maven module):
- `before_prompt_build` hook — channel context injection via `ChannelClient`
- `before_tool_call` hook — auto-commit for agent turns (when `casehub.autoCommit: true`)
- `agent_end` hook — auto-close commitment at turn end; fails open on Quarkus unavailability
- `session_start` hook — inject open commitments from prior sessions into agent context
- `commitment-manager.ts` — commitment lifecycle management (auto-commit flag, per-turn commitment flag, crash recovery via session_start)

**`skills/`** owns (OpenClaw SKILL.md files — not a Maven module):
- `casehub-global/SKILL.md` — always-active (`always: true`) CaseHub protocol awareness
- `casehub-workitem/SKILL.md` — create tracked work items via `casehub_create_workitem` MCP tool
- `casehub-case/SKILL.md` — open governed multi-step workflows via `casehub_open_case`
- `casehub-queue/SKILL.md` — route tasks to named queues via `casehub_queue`
- `casehub-status/SKILL.md` — query commitment state via `casehub_status`
- `casehub-reject/SKILL.md` — decline a tracked commitment via `casehub_reject`
- `casehub-block/SKILL.md` — extend Watchdog deadline while blocked on an external dependency via `casehub_block`
- `casehub-delegate/SKILL.md` — intentionally transfer a commitment to a named party via `casehub_delegate`
- `README.md` — ClawHub listing document

---

## Key Integration Points

### OpenClaw Hook API

```
POST /hooks/agent
{
  "message": "<prompt>",
  "agentId": "<openclaw-agent-id>",
  "deliver": "webhook",
  "to": "<qhorus-channel-endpoint>",
  "model": "claude-opus-4-5",
  "timeoutSeconds": 120
}
```

`deliver: webhook` instructs OpenClaw to POST the agent result to the URL specified in `to` — the Qhorus channel delivery endpoint in `app/`. This is the only delivery mode used by casehub-openclaw. Do not use `deliver: sync` for in-case steps.

**Two invocation modes (never mix them):**
- **Heartbeat mode** — OpenClaw autonomous monitoring → creates a CaseHub case. This is an OpenClaw-initiated flow. Do not use heartbeat invocations for steps within an already-running CaseHub case.
- **Direct call mode** — CaseHub case step → `POST /hooks/agent`. This is the normal in-case invocation path.

Full API contract: `docs/specs/openclaw-integration.md`.

### Research and Integration Model

Before implementing anything in this repo, read these in order:

| Document | What it covers |
|----------|---------------|
| `../parent/docs/specs/2026-05-25-openclaw-casehub-integration.md` | Research spec — full integration design, OpenClaw API surface, Python SDK hook model |
| `docs/specs/openclaw-integration.md` | This repo's integration model — SPI mapping, ChannelContextWindow design, REST API contract |
| `docs/specs/openclaw-skill-pack.md` | Skill pack design — what skills the Python component ships, how they compose |

---

## Key Protocols — Read Before Implementing

These protocols from the casehub garden apply directly to this repo. Read the relevant ones before starting any implementation:

| Protocol | When it applies |
|----------|----------------|
| `PP-20260524-a8f597` — casehub-platform scope rule | Any dependency on `casehub-platform`; test deps in libraries, runtime deps in app only |
| `PP-20260524-10efef` — Flyway ledger migration locations | Any new Flyway migration in this repo |
| `message-service-dispatch-enforcement-gate.md` | Any code that writes to a Qhorus channel — `dispatch()` is the only write gate |
| `auth-retrofit-readiness.md` | Any auth or gateway topology question — Claudony is the auth entry point, not this repo |
| `alternative-extension-patterns.md` | Any `@Alternative` CDI wiring |
| `PP-20260612-520281` — delivery-webhook-cross-tenant-reads.md | Any `/openclaw/delivery/*` endpoint reading Qhorus entities — use `@CrossTenant` stores only |
| `PP-20260615-11b9d2` — normative-layout-single-source.md | Any code creating or configuring Qhorus channels per case — changes to the normative layout go through `OpenClawNormativeLayout` only |

Protocol files live at: `../garden/docs/protocols/casehub/`

---

## ChannelContextWindow Design Constraints

The ChannelContextWindow sits on the intelligence path, not the correctness path. These constraints are non-negotiable:

- **MessageObserver must never throw to Qhorus.** Wrap all window-update logic in try/catch, log the error, and return normally. A failed observation is tolerable; a propagated exception is not.
- **Ring buffer overflow:** When the buffer is full and a new message arrives, evict the oldest entry and update `lastEvictionWindowSeq`. The Python SDK injects an overflow notice when `lastEvictionWindowSeq > since` — never silently drops. Overflow notice and available messages are additive, not mutually exclusive.
- **Sequence cursor:** Use the internal `windowSeq` (a global `AtomicLong` assigned by `ChannelContextWindowService`), not Qhorus's per-channel `sequenceNumber`. Qhorus sequenceNumber is per-channel and restarts at 1 per channel — unusable as a cross-channel cursor. `WindowContent.currentWindowSeq` enables the Python SDK to detect service restarts (`since > currentWindowSeq` → reset cursor to 0).
- **Cache unavailable — fail open.** If `GET /channel-context/{agentId}` returns an error or times out, the `before_prompt_build` hook logs the failure and continues the agent turn without injected context. Never block the agent turn on context retrieval.
- **Two-layer reliability model:** Qhorus is the correctness layer — messages are durable and sequenced. ChannelContextWindow is the intelligence layer — best-effort, lossy, time-bounded. Never conflate them. Do not use ChannelContextWindow as a message delivery mechanism.

Full design: `docs/specs/openclaw-integration.md` §ChannelContextWindow.

---

## Design Phase References

Read these **before designing**, not after.

### SPI and API design

| Concern | Read first |
|---------|-----------|
| Designing a new CaseHub SPI implementation | `../parent/docs/PLATFORM.md` — capability ownership, boundary rules |
| Placing code in `core/` vs `casehub/` vs `app/` | Module detail section above; `PP-20260524-a8f597` for test/runtime scope rule |
| Designing a new REST endpoint | `docs/specs/openclaw-integration.md` — existing API contract; check for duplication first |
| Adding a new OpenClaw skill or plugin hook | `docs/specs/2026-05-31-epic7-skill-pack-design.md` — four-layer architecture; `docs/specs/openclaw-skill-pack.md` — original research reference |

### Foundation integration

| Concern | Read first |
|---------|-----------|
| casehub-qhorus (channels, MessageObserver, dispatch) | `../parent/docs/repos/casehub-qhorus.md`; dispatch enforcement gate protocol |
| casehub-engine (WorkerProvisioner, CasePlanModel) | `../parent/docs/repos/casehub-engine.md` |
| casehub-platform (scope, CDI patterns) | `PP-20260524-a8f597`; `alternative-extension-patterns.md` |
| Auth / gateway topology | `auth-retrofit-readiness.md` — do not add auth logic here |

### Persistence and migrations

| Concern | Read first |
|---------|-----------|
| New Flyway migration | `PP-20260524-10efef` — migration location rules |
| ChannelContextWindow persistence | In-memory ring buffer only — no JPA, no Flyway for the window itself |

### Testing

| Concern | Read first |
|---------|-----------|
| `@QuarkusTest` setup | `../garden/docs/protocols/universal/quarkus-test-database.md` |
| Testing MessageObserver (must not throw) | Unit test: inject a mock Qhorus publisher; assert the observer catches and logs, never rethrows |
| Testing ChannelContextWindow overflow | Unit test: fill to capacity + 1; assert eviction notice in output |
| Testing the Python hook | `python/` has its own `pytest` suite; run separately from Maven |

---

## Ecosystem Conventions

**Quarkus version:** All projects use `3.32.2`. When bumping, bump all projects together.

**GitHub Packages — dependency resolution:**
```xml
<repository>
  <id>github</id>
  <url>https://maven.pkg.github.com/casehubio/*</url>
  <snapshots><enabled>true</enabled></snapshots>
</repository>
```
CI must use `server-id: github` + `GITHUB_TOKEN` in `actions/setup-java`.

**Java on this machine:**
```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26)
JAVA_HOME=/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home  # native only
```

**Use `mvn` not `./mvnw`** — maven wrapper not configured on this machine.

**Multi-module test scoping:** Always scope Maven with `-pl <module> -am`. When combining `-am` with `-Dtest=ClassName`, add `-Dsurefire.failIfNoSpecifiedTests=false` to prevent upstream modules failing when they have no matching tests.

---

## Build Commands

```bash
# Build all Maven modules
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn --batch-mode install

# Build a specific module (core, casehub, or app)
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn --batch-mode install -pl core

# Test only (app module)
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn --batch-mode test -pl app

# Test a single class
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn --batch-mode test -pl core -Dtest=ChannelContextWindowServiceTest -Dsurefire.failIfNoSpecifiedTests=false

# Python component (separate toolchain — not part of Maven build)
cd python && pip install -e ".[dev]" && pytest
```

---

## Development Workflow

### Platform Coherence

Before implementing any feature, SPI, data model, or abstraction — run the Platform Coherence Protocol in `../parent/docs/PLATFORM.md`. Check capability ownership, boundary rules, and consistency with existing patterns.

### TDD

Every implementation plan must include tests at all levels:
- **Unit tests** — pure logic, no I/O, fast (ChannelContextWindow ring buffer, eviction, cursor logic)
- **`@QuarkusTest`** — CDI wiring, REST endpoints, SPI integration
- **MessageObserver robustness tests** — assert the observer never throws regardless of input
- **Python pytest** — `before_prompt_build` hook logic, fail-open behaviour, HTTP client

Tests are not optional and are not deferred. They are part of the implementation plan from the start.

### IntelliJ MCP Tools

Two IntelliJ MCPs are available: `mcp__intellij` and `mcp__intellij-index`.

**Always check both are available before starting implementation work.** If either is unavailable, stop and report before proceeding.

**Prefer IntelliJ tools over Bash** for all operations they support — symbol search, rename refactoring, find references, go to definition, build, diagnostics. IntelliJ tools are more correct than shell equivalents.

| Operation | Use IntelliJ tool, not Bash |
|-----------|----------------------------|
| Find a class, symbol, or file | `ide_find_class`, `ide_find_file`, `ide_search_text` |
| Navigate to a definition | `ide_find_definition` |
| Find all references before renaming/deleting | `ide_find_references` |
| Rename a symbol across the project | `ide_refactor_rename` |
| Move a file | `ide_move_file` |
| Check for errors in a file | `ide_diagnostics` |
| Build the project | `build_project` |

Only use Bash when the operation is outside IntelliJ's scope: git commands, Maven, file creation, shell scripts.

### Code Review

Before marking any task complete, invoke `superpowers:requesting-code-review` to review the implementation for quality, correctness, and platform consistency.

Before designing: `superpowers:brainstorming`
Before implementing: `superpowers:test-driven-development`
Before committing: `superpowers:requesting-code-review`

Full norms: `~/.claude/design-implementation.md`

### Documentation Maintenance

After any code change, check and update:

1. **This CLAUDE.md** — does any section describe something that no longer exists or no longer matches the code?
2. **`docs/specs/openclaw-integration.md`** — reflects the current integration design and REST API contract
3. **Cross-references** — any path or URL referenced in docs: verify it resolves
4. **Drift and gaps** — code without doc coverage; docs describing removed or renamed code

If a doc update requires changes in the parent repo, create a GitHub issue on `casehubio/parent` — do not commit to that repo directly.

---

## Work Tracking

**Issue tracking:** enabled
**GitHub repo:** casehubio/openclaw

**Automatic behaviours:**
- Before implementation begins — check for an active issue. If none, run issue-workflow Phase 1 before writing any code.
- Before any commit — confirm issue linkage.
- All commits reference an issue — `Refs #N` or `Closes #N`. No commit may be made without an issue reference.
- All commits must reference the parent epic — include the epic issue number in the commit message or PR description.

---

## Project Artifacts

Paths that are project content (not workspace noise). Skills use this to avoid filtering or dropping commits that touch these paths.

| Path | What it is |
|------|------------|
| `CLAUDE.md` | Project conventions |
| `ARC42STORIES.MD` | Permanent architecture record (§1–§13); supersedes `LAYER-LOG.md` as canonical record |
| `LAYER-LOG.md` | Historical Epic log (superseded by ARC42STORIES.MD; retained as reference) |
| `docs/specs/` | Integration specs and design records (`openclaw-integration.md`, `openclaw-skill-pack.md`, `2026-05-31-epic7-skill-pack-design.md`) |
| `docs/adr/` | Architecture decision records (ADR-0001: hook language; ADR-0002: MCP host process) |
| `skills/` | OpenClaw SKILL.md files (casehub-global, workitem, case, queue, status, reject, block, delegate) |
