# Epic 7 — CaseHub OpenClaw Skill Pack Design

**Status:** Design — approved for implementation
**Issue:** casehubio/openclaw#7
**Date:** 2026-05-31
**Supersedes:** `openclaw-skill-pack.md` (original direction, pre-research)
**Critique:** `openclaw-skill-pack-critique.md`
**ADR:** `adr/0002-mcp-server-host-process.md` (Quarkus-embedded MCP, not TypeScript process)
**Tracked risk:** casehubio/openclaw#18 (`after_tool_call` embedded run bug)

---

## 1. Purpose and Positioning

The CaseHub skill pack makes OpenClaw agents accountable — not just capable. It is Direction 2
of the bidirectional integration model: OpenClaw agents calling CaseHub rather than CaseHub
provisioning OpenClaw workers.

**The core problem the original design got wrong:** the commitment lifecycle
(`casehub-commit` / `casehub-done`) cannot be delegated to the LLM. LLMs are not reliable
state machines. Session resets lose commitment IDs. Instruction blocks are followed
inconsistently. See `openclaw-skill-pack-critique.md` for the full analysis.

**The revised approach:** move state management to infrastructure — the Quarkus app's
embedded MCP endpoint and plugin hooks — and reserve SKILL.md files for stateless,
single-call operations where the LLM is the right actor.

**What OpenClaw provides (confirmed by research):**

| Capability | Status |
|---|---|
| MCP server support via MCPorter (HTTP/SSE/streamable-HTTP) | Shipped Feb 2026 |
| `before_tool_call` plugin hook — `event.toolName` confirmed | Confirmed, working |
| `after_tool_call` plugin hook | Confirmed, bug in embedded runs (tracked: openclaw#18) |
| `agent_end` plugin hook | Confirmed, working — fallback for commitment close |
| `session_start` plugin hook | Confirmed, working |
| `heartbeat_prompt_contribution` plugin hook | Confirmed, working |
| Global skill install (`--global`, `always: true`) | Confirmed, working |

---

## 2. Architecture — Three Active Layers + One Deferred

```
Layer 0  Quarkus MCP endpoint   CaseHub commitment tools + resources (embedded in app/)
Layer 1  Plugin extension       auto-commitment via hooks; calls Quarkus REST directly
Layer 2  Global skill           always-in-context CaseHub protocol awareness (no commit initiation)
Layer 3  Stateless skills       explicit user-initiated REST calls (SKILL.md)
Layer 4  Patch skills           deferred to Epic 8 — pattern documented in §7
```

Layers 0 and 1 handle everything stateful. Layers 2 and 3 handle everything the user
explicitly requests. No SKILL.md file manages state. Layer 4 is documented but not
implemented in Epic 7.

**Key architectural decisions (see ADR-0002):**
- MCP server is embedded in the Quarkus `app/` module, not a separate TypeScript process
- Plugin calls Quarkus REST directly for commitment operations — same pattern as
  `channel-client.ts` for channel context; no `mcp-client.ts`
- Single port (8080) for both REST and MCP; single base URL in OpenClaw config

---

## 3. Layer 0 — Quarkus MCP Endpoint (`app/`)

The `app/` module gains an MCP endpoint using the `quarkus-mcp-server` extension (mcp4j),
served at `POST /mcp` (streamable-HTTP transport). Tool implementations are CDI method
calls — no network hop, same service beans used by existing REST resources.

### 3.1 OpenClaw Configuration

```json
{
  "mcp": {
    "servers": {
      "casehub": {
        "transport": "streamable-http",
        "url": "http://localhost:8080/mcp"
      }
    }
  },
  "plugins": {
    "casehub-openclaw": {
      "baseUrl": "http://localhost:8080",
      "timeoutMs": 3000,
      "casehub": { "autoCommit": false }
    }
  }
}
```

`codex.agents` omitted → available to all agents globally. Operators who want to scope it
to specific agents add `"codex": { "agents": ["finance-agent"] }`.

### 3.2 Tools

All tools return structured JSON. No text parsing. The LLM receives typed results.

#### `casehub_commit`

```
input:  { task: string; deadline?: string; channelId?: string }
output: { commitmentId: string; watchdogDeadline: string }
```

Registers a Commitment in CaseHub for the named task. Arms the Watchdog. Returns
`commitmentId` as a typed field — no parsing, no hallucination.

`channelId` behaviour: when provided, the tool sends a RESPONSE speech act to that Qhorus
channel acknowledging the COMMAND. This closes the COMMAND→RESPONSE loop in Qhorus.

**channelId storage:** the Quarkus MCP handler maintains an in-memory map of
`commitmentId → channelId`. When `casehub_done` fires, it looks up the channelId from
this map and posts a DONE speech act to the originating channel. The map is keyed only
by commitmentId; it is cleared when the commitment closes.

**Crash gap:** if the Quarkus app restarts between `casehub_commit` and `casehub_done`,
the in-memory map is lost. The commitment remains open in CaseHub (the Watchdog fires
if unresolved), but the DONE speech act will not be posted to Qhorus. The Commitment is
still closeable via `casehub_done` — only the Qhorus notification is lost. This is
acceptable for Epic 7; storing channelId in the casehub-engine commitment entity
(requiring a schema change) is deferred to a future epic.

**Auto-commit limitation:** when `autoCommit: true`, the plugin opens a commitment via
`before_tool_call` without a `channelId` — the plugin does not have access to the
originating Qhorus channel at hook time. Therefore: **auto-committed turns do not post
RESPONSE or DONE speech acts to Qhorus.** COMMAND→RESPONSE→DONE channel loop is only
closed when the LLM explicitly calls `casehub_commit` with a `channelId`. This is a
stated design boundary, not a bug. The commitment is tracked and ledgered either way;
only the Qhorus notification is absent for auto-committed turns.

#### `casehub_done`

```
input:  { commitmentId: string; outcome?: string; channelId?: string }
output: { closed: true; ledgerSeq: number }
```

Closes the Commitment. Disarms the Watchdog. Records in the ledger. Posts a DONE speech
act to the originating Qhorus channel if `channelId` is in the MCP handler's in-memory
map for this `commitmentId` (stored at `casehub_commit` time). If the map entry is
absent (Quarkus restarted since commit), DONE is recorded in CaseHub but the Qhorus
notification is not sent — see channelId storage note in `casehub_commit`.

#### `casehub_reject`

```
input:  { commitmentId: string; reason: string }
output: { declined: true }
```

DECLINE speech act. Closes the Commitment without completing the task. Reason is
recorded in the ledger. Posts DECLINE to the originating channel if channelId is stored.

#### `casehub_checkpoint`

```
input:  { commitmentId: string; note: string }
output: { watchdogReset: true; newDeadline: string }
```

Mid-task progress update. Resets the Watchdog TTL. Prevents false escalation on
long-running tasks.

#### `casehub_escalate`

```
input:  { commitmentId: string; reason: string; toAgent?: string }
output: { escalated: true; escalationId: string }
```

Transitions the Commitment to ESCALATED state in CaseHub. Does not close it. The
escalation target (human or named agent) is responsible for eventual close.

Plugin behaviour on this call: the `before_tool_call` hook intercepts `casehub_escalate`
by matching `event.toolName === "casehub_escalate"` and clears the turn's
`turnCommitmentId` so `agent_end` does not attempt to auto-close the escalated
commitment. See §4.3.

**Watchdog on escalated commitments — design decision:** the Watchdog continues to run
on commitments in ESCALATED state. This is intentional: if the escalation target (human
or named agent) does not resolve the commitment before the deadline, CaseHub escalates
again. Deadline enforcement applies to escalations. The escalation target is responsible
for closing the commitment (via `casehub_done`) or requesting an extension. This is not
a gap — it is the correct behaviour for SLA-governed escalations.

#### `casehub_create_workitem`

```
input:  { description: string; deadline: string; assignee?: string; queueName?: string }
output: { workitemId: string; deadline: string; watchdogArmed: boolean }
```

Creates a WorkItem in casehub-work with SLA enforcement. `assignee` and `queueName`
are mutually exclusive — providing both is an error returned to the LLM.

#### `casehub_open_case`

```
input:  { description: string; planId?: string }
output: { caseId: string; stage: string; planName: string }
```

Starts a CasePlanModel. When `planId` is omitted, calls `GET /engine/plans?q={description}`
to find the best-match plan. If zero plans match, returns an error with the list of
available plan names so the LLM can present them to the user. Returns case ID and
confirms CaseHub now orchestrates subsequent steps.

#### `casehub_status`

```
input:  { id: string; kind?: "workitem" | "case" | "commitment" }
output: { id: string; kind: string; state: string; assignee?: string; deadline?: string; pendingActions: string[] }
```

`kind` defaults to auto-detect via parallel lookup. When `id` is a name rather than an
opaque ID, uses search endpoints (`GET /work/items?q={id}` and `GET /engine/cases?q={id}`).

#### `casehub_queue`

```
input:  { description: string; queueName: string; priority?: "normal" | "high" }
output: { routed: true; workitemId: string; queueName: string }
```

Routes a WorkItem to a named Qhorus channel queue without specifying an assignee.

### 3.3 Resources

#### `casehub://agent/{agentId}/commitments`

Open Commitments for the agent. Injected by `session_start` hook automatically; also
readable on demand. Prevents the agent from forgetting open obligations across session
resets.

```json
{
  "open": [
    {
      "commitmentId": "c-abc123",
      "task": "confirm boiler service",
      "deadline": "2026-06-03T17:00:00Z",
      "watchdogArmed": true,
      "state": "OPEN"
    }
  ],
  "count": 1
}
```

States returned: `OPEN`, `ESCALATED`. Closed commitments are not included.

#### `casehub://agent/{agentId}/cases`

Active CasePlanModel cases where the agent is a CaseHub worker. Requires resolving
OpenClaw `agentId` to a CaseHub `workerId` via `GET /engine/workers?agentId={agentId}`.
If no mapping exists (agent not yet provisioned as a CaseHub worker), returns
`{ "active": [], "count": 0 }` — not an error.

#### `casehub://channel/{channelId}/recent`

Recent channel messages for the named Qhorus channel. Backed by the existing
`ChannelContextWindowService`. Complements the `before_prompt_build` plugin hook —
provides on-demand retrieval. Replaces the `casehub-context` standalone skill from the
original design.

### 3.4 Implementation Structure

```
app/src/main/java/.../
├── OpenClawDeliveryResource.java       ← existing
├── ChannelContextWindowResource.java   ← existing
├── EvictionScheduler.java              ← existing
└── mcp/
    ├── CasehubMcpServer.java           ← mcp4j entry point / server registration
    ├── CommitmentTools.java            ← casehub_commit, casehub_done, casehub_reject,
    │                                      casehub_checkpoint, casehub_escalate
    ├── WorkitemTools.java              ← casehub_create_workitem, casehub_queue
    ├── CaseTools.java                  ← casehub_open_case
    ├── QueryTools.java                 ← casehub_status
    └── resources/
        ├── CommitmentsResource.java    ← casehub://agent/{id}/commitments
        ├── CasesResource.java          ← casehub://agent/{id}/cases
        └── ChannelRecentResource.java  ← casehub://channel/{id}/recent
```

`pom.xml` addition: `io.quarkiverse.mcp:quarkus-mcp-server` dependency.

### 3.5 Error Handling

All tools return structured errors to the LLM — never unhandled exceptions. Standard
error shape:

```json
{ "error": "PLAN_NOT_FOUND", "message": "No plan matched 'travel booking'. Available: [contractor-cycle, appointment-booking, travel-planning]" }
```

Error codes per tool:
- `casehub_commit`: `CASEHUB_UNAVAILABLE`, `INVALID_DEADLINE`
- `casehub_done` / `casehub_reject`: `COMMITMENT_NOT_FOUND`, `COMMITMENT_ALREADY_CLOSED`
- `casehub_open_case`: `PLAN_NOT_FOUND` (includes available plan list), `CASEHUB_UNAVAILABLE`
- `casehub_create_workitem`: `INVALID_DEADLINE`, `ASSIGNEE_AND_QUEUE_CONFLICT`, `CASEHUB_UNAVAILABLE`
- `casehub_status`: `NOT_FOUND`

---

## 4. Layer 1 — Plugin Extension (`plugin/`)

Extends the existing TypeScript plugin with four additional hooks. The plugin calls
Quarkus REST directly for all commitment operations — same HTTP client pattern as the
existing `ChannelClient`. No `mcp-client.ts`.

### 4.1 Commitment Granularity — Per Turn

One commitment per agent turn, not per tool call. With `autoCommit: true`:
- First `before_tool_call` in a turn: open a commitment, store the `commitmentId` as
  `turnCommitmentId` on the agent context
- Subsequent `before_tool_call` calls in the same turn: `turnCommitmentId` already set —
  skip
- `agent_end`: close `turnCommitmentId` if set and not escalated; clear it

This represents one user-visible unit of work, not internal tool dispatch. The ledger
records one commitment per turn. The Watchdog fires once per turn, not once per tool.

### 4.2 `before_tool_call` — Auto-Commit and Escalation Interception

Two responsibilities:

**Auto-commit** (when `autoCommit: true` and `turnCommitmentId` not yet set):
- Skip read-only tools: `casehub_status`, `casehub_queue` (routing only, no obligation)
- Call `POST /commitments` on Quarkus REST API with turn description (derived from
  the tool input or current session message)
- Store returned `commitmentId` as `turnCommitmentId`

**Escalation interception** (when tool is `casehub_escalate`):
- Clear `turnCommitmentId` so `agent_end` does not auto-close the escalated commitment
- The Commitment remains open in CaseHub in ESCALATED state

`autoCommit` is `false` by default. Operators enable per-agent:

```json
{
  "agents": {
    "list": [{ "id": "home-agent", "casehub": { "autoCommit": true } }]
  }
}
```

### 4.3 `agent_end` — Auto-Done

If `turnCommitmentId` is set (commitment was auto-opened this turn and not escalated),
call `POST /commitments/{id}/done` on Quarkus REST. Clear `turnCommitmentId`.

If `turnCommitmentId` is unset (no auto-commit, or escalated), do nothing.

This is the fallback for `after_tool_call` (embedded-run bug — casehubio/openclaw#18).
When that bug is fixed upstream, the plugin can be updated to use `after_tool_call` for
per-tool granularity if desired. For Epic 7, `agent_end` is the close point.

**Crash recovery:** on plugin startup, the plugin calls
`GET /channel-context/{agentId}` (existing endpoint) and, if `autoCommit` is enabled,
reads `casehub://agent/{agentId}/commitments` to check for any OPEN commitments from
before the crash. These are logged and injected into the next `session_start` context
rather than auto-closed — the agent decides what to do with orphaned commitments. Auto-
closing orphaned commitments without knowing task outcome is unsafe.

### 4.4 `session_start` — Open Commitment Injection

Reads `casehub://agent/{agentId}/commitments` and injects any open commitments:

```
## Open CaseHub Commitments

You have 1 open commitment from a previous session:
- c-abc123: "confirm boiler service" — due 2026-06-03T17:00:00Z (Watchdog armed, state: OPEN)

Address this before beginning new work: call casehub_done if complete, casehub_checkpoint
if still in progress, or casehub_reject if it cannot be completed.
```

If `count: 0`, no injection. Solves the session-boundary problem: the agent always knows
its open obligations regardless of session resets.

### 4.5 `heartbeat_prompt_contribution`

For heartbeat agents (background monitors), injects a compact summary:

```
CaseHub: 1 open commitment. Use casehub_status for details.
```

Heartbeat turns are context-constrained; this gives awareness without the full
`session_start` injection.

### 4.6 Plugin File Changes

```
plugin/src/
├── index.ts                ← add hook registrations
├── channel-client.ts       ← unchanged
├── formatters.ts           ← unchanged
├── types.ts                ← extend with CommitmentEntry, TurnState, AgentConfig
└── commitment-manager.ts   ← NEW: per-turn commitment flag, auto-commit/done logic,
                               Quarkus REST calls for commitment operations,
                               escalation interception, crash recovery
```

`CasehubClient` (extracted from `channel-client.ts` or added to it) handles:
- `POST /commitments` → open commitment
- `POST /commitments/{id}/done` → close commitment
- `GET /commitments?agentId={id}&state=OPEN` → crash recovery and session_start

**API key:** `CASEHUB_API_KEY` is read from environment in one place (`commitment-manager.ts`)
and passed to `CasehubClient`. The same key is used by `casehub_rest_client.sh` (Layer 3
skills). Key rotation requires updating one env var; both clients pick it up automatically.

---

## 5. Layer 2 — Global Skill (`skills/casehub-global/SKILL.md`)

Installed globally (`openclaw skills install --global`) with `always: true`. Its content is
injected into every agent's system prompt on every turn.

**Scope:** protocol awareness and available tools. NOT commitment initiation. The global
skill never instructs the LLM to call `casehub_commit` — that is handled by auto-commit
(Layer 1) or explicit LLM tool calls (Layer 0). This eliminates the double-commitment risk
when `autoCommit: true`.

### 5.1 Frontmatter

```yaml
---
name: casehub-global
description: CaseHub accountability protocol awareness — available tools and when to use them
version: 1.0.0
always: true
tools:
  - casehub_commit
  - casehub_done
  - casehub_reject
  - casehub_checkpoint
  - casehub_escalate
  - casehub_status
permissions: []
---
```

### 5.2 Instruction Block (~280 tokens)

```markdown
## CaseHub — Active

CaseHub provides commitment tracking, SLA enforcement, and audit trails for your work.
Every commitment you register has a Watchdog: if DONE does not arrive before the deadline,
CaseHub escalates automatically.

**Available tools:**
- `casehub_commit(task, deadline?, channelId?)` — register a commitment and arm the Watchdog
- `casehub_done(commitmentId, outcome?)` — close a commitment; disarms Watchdog; ledgered
- `casehub_reject(commitmentId, reason)` — decline a task you cannot complete
- `casehub_checkpoint(commitmentId, note)` — report progress; resets the Watchdog TTL
- `casehub_escalate(commitmentId, reason, toAgent?)` — route to human or named agent

**When to call these explicitly:**
Call `casehub_commit` when you receive a COMMAND and are personally taking responsibility
for it — not for read-only queries or tasks already tracked by casehub_create_workitem.
Call `casehub_done` when the task is genuinely complete. Call `casehub_reject` if you
cannot proceed. Call `casehub_checkpoint` for long-running tasks to prevent false escalation.

**Open commitments** from prior sessions are injected at session start. Address them
before starting new work.
```

---

## 6. Layer 3 — Stateless SKILL.md Skills

Four skills for explicit user-initiated actions. Each is a single REST call — no state
management. `casehub-commit` and `casehub-done` are **not** Layer 3 skills; they are
handled by Layer 0 (explicit LLM tool calls) and Layer 1 (auto-commit hooks).

All four skills use `casehub_rest_client.sh` as a shared supporting resource.

### 6.1 `casehub-workitem`

```yaml
name: casehub-workitem
description: Create a tracked work item with a deadline and Watchdog in CaseHub
version: 1.0.0
triggers:
  - "track this with a deadline"
  - "this needs to be done by [date]"
  - "create a work item for"
  - "make this a task with a deadline"
  - "add a deadline to this"
tools: [casehub_rest_client]
```

**Instruction block:** extract task description, parse deadline to ISO 8601 (ask user if
ambiguous). Call `POST /work/items`. Return workitem ID and confirmed deadline.

**Error handling:**
- Unparseable deadline → ask user to clarify before calling the API
- `INVALID_DEADLINE` (past date) → report and ask for a new deadline
- `CASEHUB_UNAVAILABLE` → report failure; do not silently continue or retry

### 6.2 `casehub-case`

```yaml
name: casehub-case
description: Open a CaseHub case for a complex multi-step workflow with governance gates
version: 1.0.0
triggers:
  - "start a case for"
  - "this is a multi-step process"
  - "I need to manage this workflow"
  - "create a case plan for"
  - "open a governed workflow"
tools: [casehub_rest_client]
```

**Instruction block:** call `GET /engine/plans?q={user intent description}`. If zero
results, present the full list of available plan names to the user and ask them to select
or describe their intent differently. Do not guess. On match, call `POST /engine/cases`.
Return case ID. Inform the user that CaseHub now orchestrates subsequent steps.

**Error handling:**
- Zero plan matches → list available plans; ask user to select
- `CASEHUB_UNAVAILABLE` → report failure

### 6.3 `casehub-queue`

```yaml
name: casehub-queue
description: Route a task to a named CaseHub queue for the appropriate agent or person
version: 1.0.0
triggers:
  - "add to the [name] queue"
  - "route this to [domain]"
  - "send to finance"
  - "put this in the home queue"
  - "route this to whoever handles [domain]"
tools: [casehub_rest_client]
```

**Instruction block:** extract task description and queue name. Call `POST /work/items`
with queue routing parameter. Return confirmation with queue name and workitem ID.

**Error handling:**
- Unknown queue name → call `GET /work/queues` to list valid queues; present to user
- `CASEHUB_UNAVAILABLE` → report failure

### 6.4 `casehub-status`

```yaml
name: casehub-status
description: Query the current status of a CaseHub case, work item, or commitment
version: 1.0.0
triggers:
  - "what's the status of"
  - "has [task] been done"
  - "update on the [name] case"
  - "where are we with"
  - "what's happening with"
tools: [casehub_rest_client]
```

**Instruction block:** if the user provides an ID, call the appropriate endpoint directly.
If a name is provided, search `GET /work/items?q={name}` and `GET /engine/cases?q={name}`
in parallel. If zero results, tell the user nothing was found. If multiple results, list
them and ask which one. Format state, assignee, deadline, and pending actions as a
human-readable summary.

**Error handling:**
- Zero search results → report nothing found
- Multiple results → list and ask
- `CASEHUB_UNAVAILABLE` → report failure

---

## 7. Layer 4 — Patch Skill Pattern (documented, not implemented in Epic 7)

A patch skill wraps an existing OpenClaw skill with CaseHub commitment logic without
modifying the original. Pattern documented here; implementation is Epic 8.

```yaml
# skills/casehub-patch-calendar/SKILL.md
name: casehub-patch-calendar
description: Calendar skill with CaseHub commitment tracking
version: 1.0.0
triggers:
  - "schedule this and track the commitment"
  - "add to my calendar with CaseHub tracking"
tools: [casehub_commit, casehub_done, calendar]
```

Instruction block:
1. Call `casehub_commit` — store commitmentId
2. Execute the calendar operation
3. Call `casehub_done(commitmentId)` on success; `casehub_reject(commitmentId, reason)` on failure

Priority skills to patch in Epic 8: calendar, banking, messaging, Home Assistant, social
monitoring — the skills that appear in the README use cases.

---

## 8. Repository Structure

```
casehub-openclaw/
├── core/               ← unchanged
├── casehub/            ← unchanged
├── app/                ← EXTENDED: add mcp/ package + quarkus-mcp-server dependency
│   ├── pom.xml         ← add io.quarkiverse.mcp:quarkus-mcp-server
│   └── src/main/java/.../mcp/
│       ├── CasehubMcpServer.java
│       ├── CommitmentTools.java
│       ├── WorkitemTools.java
│       ├── CaseTools.java
│       ├── QueryTools.java
│       └── resources/
│           ├── CommitmentsResource.java
│           ├── CasesResource.java
│           └── ChannelRecentResource.java
├── python/             ← unchanged
├── plugin/             ← EXTENDED: commitment hooks + CasehubClient
│   └── src/
│       ├── index.ts
│       ├── channel-client.ts
│       ├── formatters.ts
│       ├── types.ts
│       └── commitment-manager.ts   ← NEW
└── skills/             ← NEW: skill pack
    ├── casehub-global/
    │   ├── SKILL.md
    │   └── casehub_rest_client.sh  ← shared by all Layer 3 skills
    ├── casehub-workitem/SKILL.md
    ├── casehub-case/SKILL.md
    ├── casehub-queue/SKILL.md
    ├── casehub-status/SKILL.md
    └── README.md                   ← ClawHub listing (pre-drafted)
```

---

## 9. OpenClaw End-to-End Configuration

```json
{
  "mcp": {
    "servers": {
      "casehub": {
        "transport": "streamable-http",
        "url": "http://localhost:8080/mcp"
      }
    }
  },
  "plugins": {
    "casehub-openclaw": {
      "baseUrl": "http://localhost:8080",
      "timeoutMs": 3000,
      "casehub": { "autoCommit": false }
    }
  }
}
```

Environment (one location, both plugin and skills pick it up):
```bash
CASEHUB_API_KEY=<key>
CASEHUB_BASE_URL=http://localhost:8080
```

Skill installation:
```bash
openclaw skills install --global casehub-global
openclaw skills install casehub-workitem casehub-case casehub-queue casehub-status
```

**API key appears in two places** — `CASEHUB_API_KEY` env var (plugin + MCP server) and
`casehub_rest_client.sh` (Layer 3 skills, also reads the env var). Key rotation is one
env var change; both paths pick it up automatically.

---

## 10. Testing

### Quarkus MCP Endpoint (`app/` — `@QuarkusTest`)

- Each tool called with valid input → correct Quarkus service method invoked; typed output
  returned
- `casehub_commit` with `channelId` → RESPONSE speech act dispatched to Qhorus channel
- `casehub_open_case` with zero-match plan → error includes available plan list
- `casehub_create_workitem` with both `assignee` and `queueName` → `ASSIGNEE_AND_QUEUE_CONFLICT`
- `casehub_escalate` → Commitment transitions to ESCALATED; not closed
- `casehub://agent/{id}/cases` with unprovisioned agentId → `{ "active": [], "count": 0 }`
- MCP endpoint responds on `POST /mcp` (streamable-HTTP); correct MCPorter negotiation

### Plugin (`plugin/tests/`)

- `before_tool_call` with `autoCommit: true`, no `turnCommitmentId` → commit called;
  `turnCommitmentId` set
- `before_tool_call` with `autoCommit: true`, `turnCommitmentId` already set → no second
  commit
- `before_tool_call` for read-only tool (`casehub_status`) → no commit even with
  `autoCommit: true`
- `before_tool_call` for `casehub_escalate` → `turnCommitmentId` cleared
- `agent_end` with `turnCommitmentId` set → `casehub_done` called; `turnCommitmentId`
  cleared
- `agent_end` with no `turnCommitmentId` → no calls made
- `agent_end` after escalation → no `casehub_done` called (turnCommitmentId was cleared)
- `session_start` with open commitments → injection formatted correctly
- `session_start` with no open commitments → no injection
- Quarkus unavailable during `before_tool_call` → log warning; `turnCommitmentId` NOT set;
  agent turn proceeds; `agent_end` correctly handles empty `turnCommitmentId`
- Plugin restart with OPEN commitments in CaseHub → logged and injected at next
  `session_start`; not auto-closed

### Layer 3 Skills

- `casehub-workitem`: natural language deadline parsed to ISO 8601; correct POST body;
  workitem ID returned
- `casehub-workitem`: past deadline → ask user before calling API
- `casehub-case`: zero-match plan → list of available plans presented to user
- `casehub-queue`: unknown queue → valid queue list presented
- `casehub-status`: name search with zero results → "nothing found" reported
- `casehub-status`: name search with multiple results → list presented, user asked to select

### Global Skill

- `always: true` respected: full instruction block present in every agent's system prompt
- No commitment initiation instructions in the instruction block (double-commit prevention)
- Instruction block ≤ 300 tokens measured against formatted SKILL.md content before
  publishing

---

## 11. Known Risks

| Risk | Severity | Mitigation | Tracking |
|---|---|---|---|
| `after_tool_call` does not fire in embedded runs | Medium | Use `agent_end` as fallback | openclaw#60209 + casehubio/openclaw#18 |
| `quarkus-mcp-server` (mcp4j) maturity | Medium | Fallback: standalone TypeScript MCP process (see ADR-0002) | — |
| `always: true` token cost | Low | Instruction block ≤ 300 tokens; measure before publishing | — |
| Plugin fails open leaves commitment orphaned | Low | Logged; visible at next `session_start`; Watchdog catches it | — |
| agentId → workerId mapping requires engine API | Low | `casehub://agent/{id}/cases` returns empty if no mapping; not an error | — |

---

## 12. Out of Scope — Epic 7

- Patch skills (`casehub-patch-*`) — pattern in §7; implementation is Epic 8
- `casehub-context` as SKILL.md — superseded by `casehub://channel/{id}/recent` resource
- `casehub-commit` and `casehub-done` as SKILL.md — handled by Layer 0 and Layer 1
- Phase 2 lifecycle: `casehub-reject`, `casehub-block`, `casehub-delegate` as SKILL.md
- Phase 3 multi-agent: `casehub-broadcast`, `casehub-vote`, `casehub-handoff`
- Phase 4 governance: `casehub-gate`, `casehub-policy`, `casehub-review`
- Phase 5 intelligence: `casehub-remember`, `casehub-recall`
- Phase 6 economic: `casehub-budget`, `casehub-quote`, `casehub-contract`
- Opportunity C (new Commitment for escalation target) — correct architecture; requires
  new casehub-engine API surface; deferred to Phase 2

---

## 13. README

The ClawHub listing document (`skills/README.md`) is pre-drafted. It covers: the
accountability value proposition, the Git analogy, installation steps, the four-layer
architecture, five worked use cases, the cross-agent awareness section, and the roadmap
table. Commit alongside the skill files at implementation time.
