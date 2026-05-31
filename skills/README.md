# CaseHub Skills for OpenClaw

> **AI agents that are accountable, not just capable.**

OpenClaw gives agents the skills to *do things*. CaseHub gives them the structure to be
*responsible for things* — tracked commitments, enforced deadlines, escalation when nothing
arrives, and a tamper-evident ledger of every decision made.

Installing this skill pack adds CaseHub's accountability layer to your OpenClaw agents.
Your existing skills are unchanged. Every task with a deadline or a consequence now gets
a Watchdog.

---

## The Problem This Solves

OpenClaw's execution model is fire-and-forget. An agent gets a prompt, runs a skill,
produces output. The system has no machine-readable record that an obligation exists. No
deadline. No escalation if the task silently disappears. No audit trail showing what the
agent knew when it decided.

That works fine for one-off queries. It breaks down everywhere else:

- The contractor didn't confirm. Did the agent follow up? When? What did it know?
- The boiler service was due Tuesday. Did anyone track it? Who is responsible?
- Three agents processed the same case. Which one made the consequential decision?

CaseHub solves this at the infrastructure level: commitment tracking, SLA enforcement,
Watchdog escalation, and a cryptographically chained audit ledger. This skill pack makes
all of it accessible to any OpenClaw agent with a single `add-dir casehub`.

---

## The Git Analogy

Git didn't change what developers wrote. It changed the *coordination and accountability
model* around writing code — who changed what, when, why, and in what order.

CaseHub doesn't change what your agents do. It changes the accountability model around
what they do. Every commitment is registered. Every deadline is enforced. Every decision
is recorded.

---

## Architecture — Four Layers

```
Layer 0  Quarkus MCP endpoint   CaseHub commitment tools via MCPorter (HTTP/SSE)
Layer 1  Plugin hooks           Auto-commit/done at turn level (no LLM involvement)
Layer 2  Global skill           Always-in-context CaseHub protocol awareness
Layer 3  These SKILL.md files   Explicit user-initiated actions
```

The commitment lifecycle (commit → Watchdog → done) is handled by the plugin infrastructure
(Layers 0 and 1) — the LLM does not need to manage state. SKILL.md files handle explicit
user requests only.

---

## Installation

### 1. Install the plugin

```bash
# From the casehub-openclaw plugin package
openclaw plugin install casehub-openclaw
```

### 2. Install the skills

```bash
# Global skill — always active for all agents
openclaw skills install --global casehub-global

# Stateless skills — explicit user-initiated actions
openclaw skills install casehub-workitem casehub-case casehub-queue casehub-status
```

### 3. Configure OpenClaw

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
      "casehub": {
        "autoCommit": false
      }
    }
  }
}
```

Set `autoCommit: true` to automatically open and close a commitment for every agent turn.
Leave `false` (default) if you prefer explicit commitment calls only.

### 4. Set environment variables

```bash
export CASEHUB_BASE_URL="http://localhost:8080"
export CASEHUB_API_KEY="your-api-key"
```

---

## The Five Skills

### `casehub-global` (global, always active)

Protocol awareness. Injected into every agent's system prompt on every turn. Explains
the available CaseHub tools and when to call them. Does not initiate commitments itself —
that is handled by the plugin or explicit skill invocations.

### `casehub-workitem` — Track a task with a deadline

Creates a work item in CaseHub with SLA enforcement and automatic Watchdog escalation.

**Triggers:** "track this with a deadline", "this needs to be done by Thursday",
"create a work item for", "make this a task with a deadline"

**Use it when:** a task has a deadline and consequences for missing it. Grocery order by
Wednesday. Boiler service scheduled next week. Contractor follow-up needed by Thursday.

### `casehub-case` — Open a governed multi-step workflow

Starts a CasePlanModel for complex work that needs human governance gates, conditional
branching, and multi-agent orchestration.

**Triggers:** "start a case for", "this is a multi-step process", "I need to manage this
workflow", "create a case plan for"

**Use it when:** a task is too complex for a single work item. The appointment booking
cycle. Travel planning with a budget gate. Contractor coordination end-to-end.

### `casehub-queue` — Route to a domain queue

Routes a task to a named queue without specifying an assignee. Whatever agent or person
monitors that queue picks it up.

**Triggers:** "add to the [name] queue", "route this to [domain]", "send to finance",
"put this in the home queue"

### `casehub-status` — Check what's happening

Queries the current state of an open commitment, case, or work item.

**Triggers:** "what's the status of", "has [task] been done", "where are we with",
"check the status", "what commitments do I have open"

---

## What This Looks Like in Practice

### Smart home health coordination

OpenClaw reads from a health tracker (Fitbit skill) and a pill dispenser (Home Assistant
IoT skill). `casehub-workitem` creates a work item: "confirm medication taken — 30 minute
SLA." OpenClaw's heartbeat monitors the dispenser for a confirmation signal. No
confirmation? CaseHub Watchdog fires. Escalation triggers. OpenClaw contacts the carer
via WhatsApp (messaging skill).

Every step is a native platform skill. CaseHub governs the obligation cycle.

### Financial aggregation with governance

OpenClaw pulls transactions from three bank accounts (Open Banking skills) plus an
investment portfolio summary (investment skill). Flagged transactions become
`casehub-workitem` approve/reject tasks with SLA. Consequent actions (cancel subscription,
move funds) open a `casehub-case` with an oversight gate requiring human response before
anything executes.

`casehub-commit` and `casehub-done` (via MCP tools) close the cycle with a tamper-evident
record. The ledger shows exactly what the agent knew at decision time.

### Contractor coordination

OpenClaw reads Google Calendar (calendar skill) — contractor is due Thursday. The
commitment is registered automatically (via plugin auto-commit or `casehub-commit` MCP
tool). 24h before: OpenClaw sends a WhatsApp confirmation request (messaging skill). No
response within 2h → CaseHub escalates → OpenClaw tries SMS (second messaging skill).
Day-of: heartbeat monitors the arrival confirmation signal.

Multiple native skills, one governed workflow. The audit trail shows every step.

### Energy monitoring → governed decision

OpenClaw heartbeats on energy tariff APIs (utility skill) and usage via smart meter
integration (Home Assistant skill). Better tariff detected → posts EVENT to the household
observe channel. `casehub-workitem` routes to oversight for human response before any
switching action is taken.

### Social monitoring → tiered response (enterprise)

OpenClaw heartbeats across Twitter/X, LinkedIn, Reddit (social skills) plus RSS feeds.
Severity routing via CaseHub: neutral findings → observe channel, no action; negative
mentions → `casehub-workitem` for draft response with SLA; crisis → `casehub-case` with
oversight gate requiring human response before anything posts.

---

## Cross-Agent Awareness

When the `casehub-openclaw` plugin is installed, every agent automatically receives recent
channel activity injected into its system prompt before each turn.

**Example:** finance-agent posts to the household observe channel: "Monthly discretionary
budget exhausted — essentials only until month end." Twenty minutes later, grocery-agent's
heartbeat fires. Without the plugin, grocery-agent executes the full regular shop. With it,
the budget warning is in context and the agent switches to essentials-only.

---

## The Roadmap

| Phase | What it adds |
|---|---|
| This pack | Commitment primitives — workitems, cases, queues, status |
| Phase 2 | Lifecycle robustness — reject, block, delegate, checkpoint skills |
| Phase 3 | Multi-agent coordination — broadcast, vote, handoff between agents |
| Phase 4 | Self-governance — policy checks, second-agent review, oversight gates |
| Phase 5 | Persistent intelligence — CaseMemoryStore recall across sessions |
| Phase 6 | Economic participation — budgets, quotes, contracts, invoices |

Each phase publishes new skills to ClawHub. Agents that installed the base pack benefit
immediately.

---

## Prerequisites

- OpenClaw 2.x+
- A running CaseHub instance (casehub-engine + casehub-work + casehub-qhorus)
- API key with write access to casehub-work and casehub-engine
- `CASEHUB_BASE_URL` and `CASEHUB_API_KEY` environment variables set

---

## Contributing

Issues and PRs: [github.com/casehubio/openclaw](https://github.com/casehubio/openclaw)

CaseHub platform documentation: [casehubio/parent](https://github.com/casehubio/parent)
