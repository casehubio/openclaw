# Epic 7 — CaseHub OpenClaw Skill Pack Design

**Status:** Design — approved for implementation
**Issue:** casehubio/openclaw#7
**Date:** 2026-05-31
**Supersedes:** `openclaw-skill-pack.md` (original direction, pre-research)
**Critique:** `openclaw-skill-pack-critique.md`
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

**The revised approach:** move state management to infrastructure — an MCP server and plugin
hooks — and reserve SKILL.md files for stateless, single-call operations where the LLM is
the right actor.

**What OpenClaw provides (confirmed by research):**

| Capability | Status |
|---|---|
| MCP server support via MCPorter (HTTP/SSE/streamable-HTTP) | Shipped Feb 2026 |
| `before_tool_call` plugin hook | Confirmed, working |
| `after_tool_call` plugin hook | Confirmed, bug in embedded runs (tracked: openclaw#18) |
| `agent_end` plugin hook | Confirmed, working — fallback for commitment close |
| `session_start` plugin hook | Confirmed, working |
| `heartbeat_prompt_contribution` plugin hook | Confirmed, working |
| Global skill install (`--global`, `always: true`) | Confirmed, working |

---

## 2. Architecture — Four Layers

```
Layer 0  MCP Server          casehub commitment tools + resources (HTTP/SSE)
Layer 1  Plugin extension    auto-commitment via hooks (no LLM involvement)
Layer 2  Global skill        always-in-context CaseHub behaviour protocol
Layer 3  Stateless skills    explicit user-initiated REST calls (SKILL.md)
```

Layers 0 and 1 handle everything stateful. Layers 2 and 3 handle everything the user
explicitly requests. No SKILL.md file manages state.

---

## 3. Layer 0 — MCP Server (`mcp/`)

A new TypeScript package at `mcp/` in the project root. Runs as a standalone HTTP/SSE
process. Calls the existing CaseHub REST APIs (casehub-engine, casehub-work, casehub-qhorus)
via the Quarkus app.

### 3.1 Configured in OpenClaw

```json
// openclaw.json — mcp.servers block
{
  "mcp": {
    "servers": {
      "casehub": {
        "transport": "streamable-http",
        "url": "http://localhost:8090/mcp",
        "env": { "CASEHUB_API_KEY": "${CASEHUB_API_KEY}" }
      }
    }
  }
}
```

`codex.agents` omitted → available to all agents globally. Operators who want to scope it
to specific agents add `"codex": { "agents": ["finance-agent", "home-agent"] }`.

### 3.2 Tools

All tools return structured JSON. No text parsing. The LLM receives typed results.

#### `casehub_commit`

```typescript
input:  { task: string; deadline?: string; channelId?: string }
output: { commitmentId: string; watchdogDeadline: string }
```

Registers a Commitment in CaseHub for the named task. Arms the Watchdog. Returns the
`commitmentId` as a typed field — no parsing, no hallucination. The LLM stores it in
tool_result context; the plugin caches it in memory for automatic close via `agent_end`.

#### `casehub_done`

```typescript
input:  { commitmentId: string; outcome?: string }
output: { closed: true; ledgerSeq: number }
```

Closes the Commitment. Disarms the Watchdog. Records in the ledger.

#### `casehub_reject`

```typescript
input:  { commitmentId: string; reason: string }
output: { declined: true }
```

DECLINE speech act. Closes the Commitment without completing the task. Reason is recorded
in the ledger.

#### `casehub_checkpoint`

```typescript
input:  { commitmentId: string; note: string }
output: { watchdogReset: true; newDeadline: string }
```

Mid-task progress update. Resets the Watchdog TTL. Prevents false escalation on long-running
tasks.

#### `casehub_escalate`

```typescript
input:  { commitmentId: string; reason: string; toAgent?: string }
output: { escalated: true; escalationId: string }
```

Explicitly routes to a human or named agent. Does not close the Commitment — escalation
is a state transition, not a terminal state.

#### `casehub_create_workitem`

```typescript
input:  { description: string; deadline: string; assignee?: string; channelId?: string }
output: { workitemId: string; deadline: string; watchdogArmed: boolean }
```

Creates a WorkItem in casehub-work with SLA enforcement. The agent does not need to
manage the resulting obligation — CaseHub owns it from creation.

#### `casehub_open_case`

```typescript
input:  { planId: string; params?: Record<string, unknown>; description?: string }
output: { caseId: string; stage: string }
```

Starts a CasePlanModel. Returns the case ID. CaseHub orchestrates subsequent steps
via Direction 1 (direct calls back to OpenClaw). The agent need not do anything further
unless called back.

#### `casehub_status`

```typescript
input:  { id: string; kind?: "workitem" | "case" | "commitment" }
output: { id: string; kind: string; state: string; assignee?: string; deadline?: string; pendingActions: string[] }
```

Queries a WorkItem, case, or Commitment by ID. `kind` defaults to auto-detect.

#### `casehub_queue`

```typescript
input:  { description: string; queueName: string; priority?: "normal" | "high" }
output: { routed: true; workitemId: string; queueName: string }
```

Routes a WorkItem to a named Qhorus queue without specifying an assignee.

### 3.3 Resources

Resources are real-time state the LLM can read at any point in context assembly.

#### `casehub://agent/{agentId}/commitments`

Open Commitments for the agent. Injected by `session_start` hook automatically; also
readable on demand. Prevents the agent from forgetting open obligations across session resets.

```json
{
  "open": [
    { "commitmentId": "c-abc123", "task": "confirm boiler service", "deadline": "2026-06-03T17:00:00Z", "watchdogArmed": true }
  ],
  "count": 1
}
```

#### `casehub://agent/{agentId}/cases`

Active CasePlanModel cases involving the agent as a worker. Gives the agent visibility
into what CaseHub may call it to do next.

#### `casehub://channel/{channelId}/recent`

Recent channel messages for the named channel. Complements the `before_prompt_build`
hook injection — provides on-demand retrieval for agents that want to reason explicitly
about channel history.

### 3.4 Implementation

```
mcp/
├── package.json            ← standalone npm package
├── tsconfig.json
├── src/
│   ├── index.ts            ← MCP server entry point (HTTP/SSE via @modelcontextprotocol/sdk)
│   ├── tools.ts            ← tool handler implementations
│   ├── resources.ts        ← resource handler implementations
│   ├── casehub-client.ts   ← HTTP client to Quarkus app REST APIs
│   └── config.ts           ← CASEHUB_BASE_URL, CASEHUB_API_KEY from env
└── tests/
    └── tools.test.ts
```

The MCP server calls the Quarkus app (`app/`) REST endpoints — same APIs used by the
SKILL.md skills' `casehub_rest_client`. No direct casehub-engine/casehub-work dependencies;
the Quarkus app is the single entry point.

---

## 4. Layer 1 — Plugin Extension (`plugin/`)

Extends the existing TypeScript plugin with three additional hooks. The plugin already
handles `before_prompt_build` for channel context injection; these hooks add the
commitment lifecycle.

### 4.1 `before_tool_call` — Auto-Commit

Fires before any tool executes. The plugin inspects `event.toolKind` to decide whether
to arm a commitment.

**When to commit:** when the tool is a CaseHub skill tool (`casehub_*`) that represents
a substantive task, OR when the agent config has `casehub.autoCommit: true` and the tool
is not read-only (`casehub_status`, `casehub_queue` excluded).

**Auto-commit is off by default.** Operators enable it per-agent:

```json
// openclaw.json
{
  "agents": {
    "list": [{ "id": "home-agent", "casehub": { "autoCommit": true } }]
  }
}
```

When enabled, the plugin calls `casehub_commit` against the MCP server before the tool
runs. It stores the returned `commitmentId` in `Map<agentId, Stack<commitmentId>>` —
a stack because one turn may involve multiple tool calls.

### 4.2 `agent_end` — Auto-Done

Fires after the agent turn completes. If the plugin's commitment stack for this agent is
non-empty, it calls `casehub_done` for each open commitment in LIFO order.

This is the fallback for `after_tool_call` (which has a known embedded-run bug —
casehubio/openclaw#18). When that bug is fixed upstream, the plugin will be updated to
use `after_tool_call` for per-tool granularity. `agent_end` continues as backstop.

```typescript
api.on("agent_end", async (ctx) => {
  const stack = commitmentStack.get(ctx.agentId) ?? [];
  for (const id of stack.reverse()) {
    await mcpClient.callTool("casehub_done", { commitmentId: id });
  }
  commitmentStack.delete(ctx.agentId);
});
```

### 4.3 `session_start` — Open Commitment Injection

On every session start, the plugin reads
`casehub://agent/{agentId}/commitments` from the MCP server and injects any open
commitments into the agent's initial context:

```
## Open CaseHub Commitments

You have 1 open commitment from a previous session:
- c-abc123: "confirm boiler service" — due 2026-06-03T17:00:00Z (Watchdog armed)

If this task is complete, call casehub_done("c-abc123"). If blocked, call casehub_checkpoint.
```

This solves the session boundary problem: the agent always knows its open obligations
regardless of session resets.

### 4.4 `heartbeat_prompt_contribution`

For heartbeat agents (background monitors), injects a compact CaseHub status summary:

```
CaseHub: 2 open commitments. casehub_status available for details.
```

Heartbeat turns are context-constrained; this gives the agent awareness without the full
session_start injection.

### 4.5 Plugin File Changes

```
plugin/src/
├── index.ts              ← add hook registrations (before_tool_call, agent_end, session_start, heartbeat_prompt_contribution)
├── channel-client.ts     ← unchanged
├── formatters.ts         ← unchanged
├── types.ts              ← extend with CommitmentEntry, AgentConfig
├── commitment-manager.ts ← NEW: commitment stack, auto-commit/done logic
└── mcp-client.ts         ← NEW: thin client to casehub MCP server for plugin-side calls
```

---

## 5. Layer 2 — Global Skill (`skills/casehub-global/SKILL.md`)

A single skill installed globally (`openclaw skills install --global`) with `always: true`
in frontmatter. Its full content is injected into every agent's system prompt on every turn.

### 5.1 Frontmatter

```yaml
---
name: casehub-global
description: CaseHub accountability layer — always-active commitment protocol for all agents
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

`always: true` ensures full content injection, not just name+description. No intent
matching — this skill is always active.

### 5.2 Instruction Block

```markdown
## CaseHub Accountability — Active

CaseHub is running alongside you. Every substantive task you commit to is tracked with a
deadline and a Watchdog. If DONE does not arrive before the deadline, CaseHub escalates.

### When to register a commitment

Register a commitment (casehub_commit) when:
- You receive a COMMAND via a Qhorus channel and are taking responsibility for it
- You are beginning a task with a deadline that has consequences if missed
- You are told "I'll handle this" or equivalent

Do NOT register a commitment for:
- Status queries or read-only operations
- Tasks you are not confident you can complete (use casehub_reject with a reason instead)
- Tasks already covered by casehub_create_workitem in the same turn

### The protocol

1. Receive task → call casehub_commit → store the returned commitmentId
2. Execute the task
3. On completion → call casehub_done(commitmentId)
4. If blocked → call casehub_checkpoint(commitmentId, note) to reset the Watchdog
5. If you cannot proceed → call casehub_reject(commitmentId, reason)

### Your open commitments

Open commitments are injected at session start and available via
casehub://agent/{agentId}/commitments. If you see open commitments from a prior session,
address them before beginning new work.
```

### 5.3 Supporting Resources

```
skills/casehub-global/
├── SKILL.md
└── casehub_rest_client.sh   ← shared utility called by stateless skills (Layer 3)
```

`casehub_rest_client.sh` is a thin Bash wrapper over `curl` that reads `CASEHUB_BASE_URL`
and `CASEHUB_API_KEY` from environment. All Layer 3 skills reference it as a supporting
resource so the REST client is defined once.

---

## 6. Layer 3 — Stateless SKILL.md Skills

Four skills for explicit user-initiated actions. Each is a single REST call — no state
management, no commitment IDs to track. The LLM is the right actor for these.

`casehub-commit` and `casehub-done` are **not** Layer 3 skills. They are handled by
Layer 0 (MCP tools, explicit LLM calls) and Layer 1 (plugin hooks, automatic). A SKILL.md
for commit/done would re-introduce the LLM state management problems identified in the
critique.

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
tools:
  - casehub_rest_client
```

Instruction block: extract task description, deadline (parse natural language date to ISO
8601), optional assignee. Call `POST /work/items`. Return workitem ID and confirmed deadline.
On API error: report failure, do not silently continue.

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
tools:
  - casehub_rest_client
```

Instruction block: identify the appropriate CasePlanModel from user intent — call
`GET /engine/plans?q={description}` via `casehub_rest_client` to find the best-match
plan ID. Call `POST /engine/cases` with the resolved plan ID. Return case ID. Inform
the user that CaseHub now orchestrates subsequent steps and the agent will be called
when action is required.

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
tools:
  - casehub_rest_client
```

Instruction block: extract task description and queue name. Call
`POST /work/items` with queue routing parameter. Return confirmation with queue name
and workitem ID.

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
tools:
  - casehub_rest_client
```

Instruction block: resolve the case or workitem from context (ID if available, name
search via `GET /work/items?q={name}` or `GET /engine/cases?q={name}` otherwise).
Format state, assignee, deadline, and pending actions as a human-readable summary.

---

## 7. Layer 4 — Patch Skill Pattern (documented, not implemented in Epic 7)

A patch skill wraps an existing OpenClaw skill with CaseHub commitment logic without
modifying the original. Pattern documented here; implementation deferred to Epic 8+.

```yaml
# skills/casehub-patch-calendar/SKILL.md
name: casehub-patch-calendar
description: Calendar skill with CaseHub commitment tracking
version: 1.0.0
triggers:
  - "add to my calendar with tracking"
  - "schedule this and track it"
  # original calendar triggers are retained in the base skill
tools:
  - casehub_commit
  - casehub_done
  - calendar          ← delegates to the original skill's tool
```

Instruction block:
1. Call `casehub_commit` — store commitmentId
2. Execute the calendar operation (same as the base calendar skill's instruction block)
3. Call `casehub_done(commitmentId)` on success; `casehub_reject(commitmentId, reason)` on failure

Priority skills to patch in Epic 8: calendar, banking (Open Banking), messaging
(WhatsApp/Telegram), Home Assistant, social monitoring. These cover the five use cases
in the README.

---

## 8. Repository Structure

```
casehub-openclaw/
├── core/               ← unchanged
├── casehub/            ← unchanged
├── app/                ← unchanged (Quarkus REST APIs consumed by MCP server)
├── python/             ← unchanged
├── plugin/             ← EXTENDED: add commitment hooks, mcp-client.ts, commitment-manager.ts
│   ├── src/
│   │   ├── index.ts
│   │   ├── channel-client.ts
│   │   ├── formatters.ts
│   │   ├── types.ts
│   │   ├── commitment-manager.ts   ← NEW
│   │   └── mcp-client.ts           ← NEW
│   └── tests/
├── mcp/                ← NEW: MCP server package
│   ├── package.json
│   ├── tsconfig.json
│   ├── src/
│   │   ├── index.ts
│   │   ├── tools.ts
│   │   ├── resources.ts
│   │   ├── casehub-client.ts
│   │   └── config.ts
│   └── tests/
└── skills/             ← NEW: skill pack
    ├── casehub-global/
    │   ├── SKILL.md
    │   └── casehub_rest_client.sh
    ├── casehub-workitem/
    │   └── SKILL.md
    ├── casehub-case/
    │   ├── SKILL.md
    │   └── casehub_plan_selector.sh
    ├── casehub-queue/
    │   └── SKILL.md
    ├── casehub-status/
    │   └── SKILL.md
    └── README.md                   ← ClawHub listing document
```

---

## 9. OpenClaw Configuration — End-to-End Setup

A user installing the full stack configures OpenClaw as follows:

```json
{
  "mcp": {
    "servers": {
      "casehub": {
        "transport": "streamable-http",
        "url": "http://localhost:8090/mcp"
      }
    }
  },
  "plugins": {
    "casehub-openclaw": {
      "baseUrl": "http://localhost:8080",
      "timeoutMs": 3000,
      "casehub": {
        "mcpUrl": "http://localhost:8090/mcp",
        "autoCommit": false
      }
    }
  }
}
```

Environment:
```bash
CASEHUB_API_KEY=<key>
CASEHUB_BASE_URL=http://localhost:8080
```

Skill installation:
```bash
openclaw skills install --global casehub-global
openclaw skills install casehub-workitem casehub-case casehub-queue casehub-status
```

MCP server startup (separate process):
```bash
npx casehub-openclaw-mcp
# or: docker run casehubio/casehub-openclaw-mcp
```

---

## 10. Testing

### MCP Server (`mcp/tests/`)

- **Tool contract tests:** each tool called with valid input returns correctly typed output;
  each tool called with missing required fields returns structured error
- **Resource tests:** each resource returns correct JSON shape against a stubbed Quarkus
  app
- **Auth tests:** missing or invalid `CASEHUB_API_KEY` returns 401 from MCP server, not
  an unhandled error
- **Casehub client tests:** verify correct REST endpoint called per tool, correct HTTP
  method and body shape

### Plugin Extension (`plugin/tests/`)

- **`before_tool_call`** with `autoCommit: true` → `casehub_commit` called; commitmentId
  stored in stack
- **`before_tool_call`** with `autoCommit: false` → no commit call
- **`before_tool_call`** for read-only tool (`casehub_status`) → no commit even when
  autoCommit is true
- **`agent_end`** with non-empty stack → `casehub_done` called for each stacked commitmentId
  in LIFO order; stack cleared
- **`agent_end`** with empty stack → no calls
- **`session_start`** with open commitments → open commitment notice injected into context
- **`session_start`** with no open commitments → no injection
- **Fail-open:** MCP server unreachable during `before_tool_call` → log warning, allow tool
  to proceed without commitment; do not block agent turn

### Skills (`skills/tests/` — pytest against stub Quarkus app)

- `casehub-workitem`: natural language deadline parsed to ISO 8601; POST /work/items called
  with correct body; workitem ID returned to user
- `casehub-case`: plan selector query matches intent; POST /engine/cases called; case ID
  returned; user informed CaseHub now orchestrates
- `casehub-queue`: queue name extracted; POST /work/items with queue param; confirmation
  returned
- `casehub-status`: ID-based lookup calls correct endpoint; name-based lookup uses search
  endpoint; output formatted correctly

### Global Skill

- `always: true` respected by OpenClaw skill loader (integration test with local OpenClaw
  instance)
- Instruction block present in system prompt on every turn (verify via `llm_input` hook
  in test plugin)

---

## 11. Known Risks

| Risk | Severity | Mitigation | Tracking |
|---|---|---|---|
| `after_tool_call` does not fire in embedded runs | Medium | Use `agent_end` as fallback; per-turn granularity deferred | openclaw/openclaw#60209 + casehubio/openclaw#18 |
| `always: true` token cost at scale | Low | Instruction block kept under 300 tokens; monitor at 10+ skills installed | — |
| MCP server as single point of failure | Medium | Plugin fails open — tool calls proceed without commitment if MCP is down | — |
| CaseHub API changes break MCP tools | Medium | Pin CaseHub API version in MCP server; integration tests against stub | — |
| `autoCommit` creates spurious commitments on short-lived tool calls | Low | Off by default; operators enable per-agent with awareness of implications | — |

---

## 12. Out of Scope — Epic 7

These are captured in the roadmap and will be sequenced in future epics:

- Patch skills (`casehub-patch-*`) — pattern documented in §7; implementation is Epic 8
- Phase 2 lifecycle skills: `casehub-reject`, `casehub-block`, `casehub-delegate`,
  `casehub-checkpoint` as standalone SKILL.md files
- Phase 3 multi-agent coordination: `casehub-broadcast`, `casehub-vote`, `casehub-handoff`
- Phase 4 governance: `casehub-gate`, `casehub-policy`, `casehub-review`
- Phase 5 intelligence: `casehub-remember`, `casehub-recall` (CaseMemoryStore)
- Phase 6 economic: `casehub-budget`, `casehub-quote`, `casehub-contract`
- `casehub-context` skill — superseded by `casehub://channel/{id}/recent` MCP resource
- `casehub-commit` and `casehub-done` as standalone SKILL.md skills — handled by Layer 0
  and Layer 1; SKILL.md equivalents would re-introduce LLM state management

---

## 13. README

The ClawHub listing document (`skills/README.md`) is pre-drafted. It covers: the
accountability value proposition, the Git analogy, installation steps, the seven
capabilities (MCP tools + global skill + stateless skills), five worked use cases, the
cross-agent awareness section, and the roadmap table.

Draft: produced during brainstorming session 2026-05-31.
