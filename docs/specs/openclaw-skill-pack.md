# CaseHub Skill Pack for OpenClaw

**Status:** Design reference — extracted from research spec dated 2026-05-25
**Source:** `../parent/docs/specs/2026-05-25-openclaw-casehub-integration.md`
**Scope:** Direction 2 — OpenClaw → CaseHub (optional add-on skills)
**Implementation:** Epic 7 (`casehubio/openclaw#7`)

---

## 1. Purpose and Positioning

The CaseHub skill pack enables OpenClaw users to optionally layer CaseHub coordination on top
of the existing 5,400-skill ecosystem. It is Direction 2 of the bidirectional integration model:
OpenClaw agents calling CaseHub rather than CaseHub provisioning OpenClaw workers.

**The opt-in principle:** a bare OpenClaw install is completely unchanged. Installing the
casehub skills adds CaseHub coordination capabilities without altering any existing skill or
workflow. Users who want formal commitment tracking, SLA enforcement, and audit trails get
them; users who do not need them are unaffected.

**What it provides on top of OpenClaw:**
OpenClaw's skill execution is fire-and-forget — the agent confirms it will do something and the
system has no machine-readable record that an obligation exists, no deadline, no Watchdog. The
CaseHub skill pack adds:
- Every task with a deadline becomes a WorkItem with SLA and escalation
- Acknowledgement of a COMMAND creates a tracked Commitment; DONE closes it
- Complex multi-step workflows become CasePlanModels with human governance gates
- CaseHub's commitment and audit layer sits transparently below OpenClaw skill execution

---

## 2. Distribution and Installation

### 2.1 Registry

Published to ClawHub — the OpenClaw community skill registry. Users discover and install the
skills the same way they install any other ClawHub skill.

### 2.2 Installation in OpenClaw

Users run the `add-dir` command pointing at the casehub skill directory:

```
add-dir casehub
```

This makes all seven skills available to the OpenClaw intent router. No other configuration
change is required to have the skills present.

Users then configure the CaseHub connection — endpoint and API key — in their OpenClaw
configuration. The casehub skills use this to call the CaseHub REST API.

### 2.3 Scope of Change

Installing the casehub skill pack:
- Adds seven SKILL.md files to the user's OpenClaw skill directory
- Does not modify any existing skills
- Does not change routing for any existing prompts (new trigger phrases only)
- Does not require CaseHub to be running for non-casehub skill usage

---

## 3. The Seven Skills

### 3.1 `casehub-workitem`

**What it does:** Creates a WorkItem in casehub-work from a natural language instruction,
with deadline and assignee. The WorkItem enters the standard casehub-work lifecycle — claimed,
in-progress, done, escalated — with SLA enforcement and Watchdog if the deadline is missed.

**Use it when:** a task has a deadline and consequences for missing it. Grocery order by
Wednesday. Boiler service scheduled for next week. Contractor follow-up needed by Thursday.

**Key SKILL.md fields:**

| Field | Value |
|---|---|
| `description` | Create a tracked work item with a deadline and assignee in CaseHub |
| `triggers` | "create a task with deadline", "track this with a deadline", "make this a work item", "this needs to be done by [date]" |
| `tools` | `casehub_rest_client` (POST /work/items) |

The skill extracts the task description, deadline, and optional assignee from the prompt. It
calls the casehub-work REST API to create the WorkItem and returns the WorkItem ID and deadline
confirmation to the user.

### 3.2 `casehub-case`

**What it does:** Starts a CasePlanModel in casehub-engine for a complex multi-step workflow.
The case holds the structure of the workflow — stages, tasks, human governance gates, conditional
branching — and CaseHub orchestrates subsequent steps including direct calls to OpenClaw for
skill execution.

**Use it when:** a task is too complex for a single WorkItem. The appointment booking cycle
(check → propose → confirm → book → remind → cancel-watch). Travel planning with a budget
gate. Contractor coordination (quote → compare → approve → book → confirm → pay → sign-off).
The heartbeat detects a condition and needs to hand off to a structured workflow.

**Key SKILL.md fields:**

| Field | Value |
|---|---|
| `description` | Start a CaseHub case for a complex multi-step workflow with human governance gates |
| `triggers` | "start a case for", "this is a multi-step process", "I need to manage this workflow", "create a case plan" |
| `tools` | `casehub_rest_client` (POST /engine/cases), `casehub_plan_selector` |

The skill identifies the appropriate CasePlanModel from the user's intent, calls casehub-engine
to open the case, and returns the case ID. Once the case is open, CaseHub takes over
orchestration — OpenClaw shifts from autonomous agent to orchestrated executor.

### 3.3 `casehub-queue`

**What it does:** Routes a task to a named Qhorus channel queue — home, health, finance, or
any user-configured queue name. The task enters the queue as a WorkItem without a specific
assignee; whatever agent or human monitors that queue picks it up.

**Use it when:** the task belongs to a domain but the right agent or person is not yet known,
or the routing should be determined by the queue's configuration rather than the caller. "Route
this to the finance queue." "Add this to the home maintenance queue."

**Key SKILL.md fields:**

| Field | Value |
|---|---|
| `description` | Route a task to a named CaseHub queue for the appropriate agent or person to pick up |
| `triggers` | "add to the [name] queue", "route this to [domain]", "put this in the home queue", "send to finance" |
| `tools` | `casehub_rest_client` (POST /work/items with queue routing), `casehub_queue_resolver` |

### 3.4 `casehub-status`

**What it does:** Queries the status of a running CaseHub case or WorkItem by ID, name, or
description. Returns the current state, assigned agent, deadline, and any pending actions.

**Use it when:** a user or another skill needs to know what is happening with an open case.
"What's the status of the travel planning case?" "Has the boiler service been confirmed?"
"Who is handling the contractor follow-up?"

**Key SKILL.md fields:**

| Field | Value |
|---|---|
| `description` | Query the current status of a CaseHub case or work item |
| `triggers` | "what's the status of", "has [task] been done", "update on the [name] case", "where are we with" |
| `tools` | `casehub_rest_client` (GET /work/items/{id}, GET /engine/cases/{id}) |

The skill resolves the case or WorkItem from the prompt context (by ID if available, by name
search otherwise), calls the appropriate CaseHub status endpoint, and formats a human-readable
summary of the current state.

### 3.5 `casehub-commit`

**What it does:** Acknowledges a COMMAND received via a Qhorus channel as a Commitment from
within a skill execution. This is the acknowledgement half of the COMMAND → RESPONSE → DONE
lifecycle — the agent is saying "I will do this" and opening a tracked Commitment with a
deadline and Watchdog.

**Use it when:** an agent skill receives an instruction it is taking responsibility for. Rather
than fire-and-forget, the skill registers a Commitment so CaseHub can track whether DONE
arrives before the deadline. Used inside other skills or as a standalone acknowledgement.

**Key SKILL.md fields:**

| Field | Value |
|---|---|
| `description` | Acknowledge a COMMAND as a Commitment in CaseHub, opening obligation tracking and a Watchdog |
| `triggers` | "I'll handle this", "commit to this task", "register my commitment", "I'm taking this on" |
| `tools` | `casehub_rest_client` (POST /qhorus/commitments) |

This skill is most commonly called from within another skill rather than directly — when a
skill's instruction block instructs the LLM to call `casehub-commit` as part of acknowledging
a work instruction.

### 3.6 `casehub-done`

**What it does:** Closes a Commitment in CaseHub — the DONE terminal state in the COMMAND →
RESPONSE → DONE lifecycle. Disarms the Watchdog, records completion in the ledger (if active),
and optionally forwards any output to the originating channel.

**Use it when:** a skill has completed a task that was registered as a Commitment. The
instruction block of a task-execution skill includes a call to `casehub-done` at successful
completion to close the obligation cleanly.

**Key SKILL.md fields:**

| Field | Value |
|---|---|
| `description` | Close a CaseHub commitment, recording task completion and disarming the Watchdog |
| `triggers` | "mark this done", "close this commitment", "task complete — close it", "done with [task]" |
| `tools` | `casehub_rest_client` (POST /qhorus/commitments/{id}/done) |

Like `casehub-commit`, this skill is most commonly called from within another skill's
instruction block as part of the completion sequence, rather than directly by the user.

### 3.7 `casehub-context`

**What it does:** Explicitly retrieves recent channel context from the ChannelContextWindow
REST endpoint for the current agent. Formats the result for display or for injection into
the current agent's reasoning. Used when automatic context injection via the `before_prompt_build`
hook is not active for this agent session.

**Use it when:** the automatic Python SDK hook is not installed or not active, but the agent
needs recent channel context before acting. A skill that needs to know what finance-agent
posted to the observe channel in the last 30 minutes before deciding whether to proceed.
Or an operator preference check — "what has been happening on the home channel recently?"

**Key SKILL.md fields:**

| Field | Value |
|---|---|
| `description` | Retrieve recent Qhorus channel context for the current agent from ChannelContextWindow |
| `triggers` | "what's been happening on the [channel] channel", "get recent context", "what has [agent] posted recently", "check channel activity" |
| `tools` | `casehub_rest_client` (GET /channel-context/{agentId}?since={sequenceNumber}) |

The skill calls the ChannelContextWindow REST endpoint with the current agentId and the last
known sequenceNumber. It formats the returned messages and either displays them to the user
or returns them for use in the calling skill's reasoning context.

This skill is the explicit-retrieval complement to the automatic `before_prompt_build` hook
(see integration spec §5.5). Both serve the same purpose — surfacing recent channel activity —
but through different activation paths.

---

## 4. Maximising OpenClaw Skill Value

### 4.1 The Core Distinction

Browser MCP is a generic capability — it can do anything a human can do in a browser but is
slow, vision-based, and fragile to UI changes. The pre-built OpenClaw platform skills use
native APIs, are stable, fast, and already handle authentication.

The CaseHub skill pack is most valuable when the underlying use cases leverage the pre-built
platform skill ecosystem rather than generic browser automation. The examples below use native
platform skills only; none requires browser automation.

### 4.2 Use Cases That Leverage the Skill Ecosystem

**Multi-account financial aggregation + governance**

OpenClaw pulls transactions from three bank accounts (Open Banking skills) plus investment
portfolio summary (investment skill). The CaseHub skill pack takes over: `casehub-workitem`
creates WorkItems for approve/reject flagged transactions; `casehub-case` opens an oversight
case for any consequent action (cancel subscription, move funds); `casehub-commit` and
`casehub-done` close the cycle tamper-evidently. The skill work is genuine multi-source
assembly across native banking APIs. CaseHub governs the decision cycle.

**Smart home + health coordination**

OpenClaw reads from a health tracker (Fitbit/Apple Health skill) and pill dispenser (Home
Assistant IoT skill). `casehub-workitem` creates a WorkItem "confirm medication taken — 30 min
SLA." OpenClaw heartbeat monitors the dispenser for a confirmation signal. No confirmation →
CaseHub Watchdog fires → escalation → OpenClaw contacts carer via WhatsApp (messaging skill).
Every step a genuine skill — health tracker, IoT, two messaging channels — none of it browser.

**Calendar + contractor commitment cycle**

OpenClaw reads Google Calendar (calendar skill) — contractor is due Thursday. `casehub-commit`
opens the external actor Commitment. 24h before: OpenClaw sends WhatsApp confirmation request
(messaging skill). No response within 2h → CaseHub escalates → OpenClaw tries SMS (second
messaging skill). Day-of: heartbeat monitors arrival confirmation signal. Multiple skills, one
governed workflow: calendar, two messaging channels, heartbeat monitoring.

**Energy monitoring → governed decision**

OpenClaw heartbeats on energy tariff APIs (utility skill) and monitors usage via smart meter
integration (Home Assistant skill). When a better tariff is detected: posts EVENT to household
observe channel. The EVENT reaches home-agent's context via ChannelContextWindow.
`casehub-workitem` routes to oversight for human RESPONSE before any switching action is taken.
`casehub-context` can be called explicitly if the automatic hook is not active.

**Social/news monitoring → governed response (enterprise)**

OpenClaw heartbeats across Twitter/X, LinkedIn, Reddit (social skills) plus RSS/news APIs.
Severity routing via CaseHub: neutral findings → observe channel (no action); negative
mentions → `casehub-workitem` for draft response with SLA; crisis → `casehub-case` with
oversight channel gate requiring human RESPONSE before anything posts. OpenClaw drafts and
posts once approved (social skill). OpenClaw provides the platform integrations; CaseHub
provides the governance layer.

### 4.3 The Browser Fallback

Browser-based use cases are not excluded. If a use case strongly showcases CaseHub's
accountability layer and would generate significant traction with OpenClaw's community, it is
worth pursuing. This is a separate objective from maximising the pre-built skill ecosystem.
Both objectives are valid; they should not be conflated when designing use cases.

---

## 5. Skill Pack Structure

Each skill is a directory containing a `SKILL.md` file with the three standard OpenClaw layers.

```
casehub-skills/
├── casehub-workitem/
│   └── SKILL.md
├── casehub-case/
│   └── SKILL.md
├── casehub-queue/
│   └── SKILL.md
├── casehub-status/
│   └── SKILL.md
├── casehub-commit/
│   └── SKILL.md
├── casehub-done/
│   └── SKILL.md
├── casehub-context/
│   └── SKILL.md
└── README.md
```

The `README.md` is the ClawHub listing document — installation instructions, CaseHub
endpoint configuration, prerequisites, and worked examples for each use case.

Each `SKILL.md` follows the standard three-layer format:

1. YAML frontmatter: name, description, version, triggers, required tools, permissions
2. Instruction block: step-by-step directives for the LLM — what to extract from the prompt,
   what API calls to make, what to return, how to handle errors
3. Supporting resources: shared `casehub_rest_client` utility called from all skills
