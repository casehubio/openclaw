# OpenClaw ↔ CaseHub Integration Spec

**Status:** Design reference — extracted from research spec dated 2026-05-25
**Source:** `../parent/docs/specs/2026-05-25-openclaw-casehub-integration.md`
**Scope:** Technical integration architecture for `casehub-openclaw`

---

## 1. OpenClaw Technical Architecture

### 1.1 Skill Architecture

Skills are not HTTP endpoints. A skill is a directory containing a `SKILL.md` file with three
distinct layers:

- **Layer 1 — YAML frontmatter:** name, description, version, trigger phrases, required tools,
  permissions. The runtime reads this to decide whether a skill should handle a request.
- **Layer 2 — Instruction block:** markdown content containing step-by-step AI directives —
  a scoped system prompt defining persona, procedures, output format, and validation rules.
- **Layer 3 — Supporting resources:** optional scripts (Python, Bash, TypeScript/Deno),
  configuration files, API integration code.

Skills are invoked by the Agent Core's intent router based on semantic matching against trigger
phrases in the frontmatter. External systems cannot call "run skill X by name" directly — they
send a prompt and the AI routes to the appropriate skill internally. For maximum determinism,
use a dedicated `agentId` pre-configured with only the relevant skills installed.

### 1.2 Hook API Surface

OpenClaw exposes HTTP endpoints on the Gateway for external invocation:

| Endpoint | Purpose |
|---|---|
| `POST /hooks/agent` | Full agent run — executes a prompt, can deliver reply anywhere |
| `POST /hooks/wake` | Lightweight nudge — wakes agent with a text event |
| `POST /hooks/<name>` | Custom-named endpoint mapped to wake or agent action via config |

`POST /hooks/agent` key fields:

| Field | Notes |
|---|---|
| `message` | Required. The prompt sent to the agent. |
| `agentId` | Target agent identity. |
| `wakeMode` | How to wake the agent. |
| `deliver` | Delivery mode for the result. |
| `channel` | OpenClaw messaging channel (Telegram, WhatsApp, etc.) |
| `to` | Delivery target (webhook URL or channel reference) |
| `model` | LLM backend override. |
| `timeoutSeconds` | Maximum execution time. |

**Delivery modes after an agent run:**
- `deliver: "webhook"` — POST finished result payload to an arbitrary HTTP URL
- `deliver: "announce"` — fallback-deliver final text to a chat channel
- `deliver: "none"` — no runner fallback delivery

**Authentication:** Bearer token in `Authorization` header (required). Query-string tokens
rejected (400). Always use HTTPS in production.

### 1.3 Python SDK

```python
from openclaw import OpenClawClient

client.get_agent("home-agent", session_name="household-main")
```

Context injection via `before_prompt_build` plugin hook (the v2 pattern):

```python
@agent.on("before_prompt_build")
def inject_context(ctx):
    return { "appendSystemContext": "...dynamic context..." }
```

`appendSystemContext` lands in the system prompt — rebuilt fresh every turn, never compacted.
This is the compaction-safe injection point for channel context. The `before_prompt_build` hook
is preferred over `prependContext` / `before_agent_start` (v1), which could silently disappear
if `allowPromptInjection` is disabled by an operator.

### 1.4 Pluggable Context Engine

If a plugin provides `kind: "context-engine"`, OpenClaw delegates all context assembly to that
engine. This is the deepest integration point for casehub-openclaw — the context engine can
inject Qhorus channel history, CaseMemoryStore facts, and WorkerContextProvider lineage as a
unified context package. More powerful than the hook approach; more complex to implement.

Open question: whether to implement a full context engine or use the `before_prompt_build` hook
is unresolved (research spec §12.10).

### 1.5 Session Management

OpenClaw rebuilds its system prompt from scratch on every agent run. Sessions reset daily
(4:00 AM local) and on idle timeout.

- `session:start` lifecycle hook is planned but not yet implemented (issue #48383)
- Channel history backfill on session start is an open feature request (issue #27231), not shipped
- Agents have no automatic memory of what happened in prior sessions unless context is explicitly injected

---

## 2. Two Invocation Modes — Decision Framework

### 2.1 The Core Distinction

Two fundamentally different modes exist for invoking OpenClaw from the CaseHub ecosystem.
Choosing correctly is critical to system correctness and SLA behaviour.

### 2.2 Heartbeat Mode — OpenClaw Owns the Timing Decision

Use when:
- No CaseHub case exists yet — OpenClaw is watching for a condition that should create one
- The trigger is ambient and conditional: energy prices drop below threshold, social mention
  detected, health tracker shows anomaly, flight price changes, email arrives matching pattern
- OpenClaw must reason autonomously: "is this condition met? is this worth acting on?"
- Monitoring is continuous and indefinite, not bounded by a case lifecycle
- The outcome is: create a CaseHub WorkItem or case, alert a human via messaging platform

### 2.3 Direct Call Mode — CaseHub Owns the Timing Decision

Use when:
- A case is already running and needs a specific skill executed now
- CaseHub determines timing: SLA expiry, WorkItem completion, stage transition, CDI event
- The task is deterministic — "pull this month's transactions" not "watch for transactions"
- SLA precision matters: skill must run within seconds of the trigger, not on the next heartbeat tick
- The result feeds back into the running case workflow

### 2.4 The Golden Rule

> If the question is *"when should this happen?"* — heartbeat owns the decision.
> If the question is *"do this now, as part of this case"* — CaseHub fires a direct call.

### 2.5 Pattern Comparison

| Pattern | Who decides timing | Bounded by case? | Result goes to |
|---|---|---|---|
| Heartbeat | OpenClaw | No | Creates WorkItem / case |
| Direct call | CaseHub | Yes | Case step result |
| Hybrid | OpenClaw starts, CaseHub continues | OpenClaw: No → CaseHub: Yes | Both |

### 2.6 The Hybrid Pattern

Heartbeat detects condition → calls `casehub-case` or `casehub-workitem` skill → CaseHub case
opens → CaseHub orchestrates subsequent steps via direct calls. OpenClaw shifts from autonomous
agent to orchestrated executor the moment a case opens.

### 2.7 Direct Call Example

```json
POST /hooks/agent
Authorization: Bearer SECRET

{
  "message": "Pull this month's transactions from all three linked accounts and categorise by spend type",
  "agentId": "finance-agent",
  "deliver": "webhook",
  "to": "https://casehub.internal/openclaw/delivery/channel/{channelId}",
  "timeoutSeconds": 30
}
```

OpenClaw executes the banking skill, generates output, POSTs the result to the CaseHub channel
endpoint. The Qhorus channel receives it as a typed speech act. No heartbeat involved.

---

## 3. Qhorus ↔ OpenClaw Channel Wiring

### 3.1 Terminology Collision

OpenClaw "channels" = messaging platform connections (Telegram, WhatsApp, Slack, Discord, iMessage,
Teams, Signal). Qhorus "channels" = typed normative communication channels in the accountability
layer. Same word, completely different concepts.

In the wired architecture these are complementary: a Qhorus oversight channel routes a human
decision *via* OpenClaw's WhatsApp delivery. One is the normative structure; the other is the
delivery mechanism.

### 3.2 Write Path — OpenClaw to Qhorus

**Completion signaling (tool-call-first, openclaw#28):**
During the agent turn, the agent calls `casehub_done(agentId, commitmentId, outcome)` via MCP →
`CommitmentTools.done()` dispatches DONE (with `inReplyTo=COMMAND`, `correlationId=commitmentId`)
to `MessageService.dispatch()` → commitment FULFILLED. The commitmentId is provided in the COMMAND
message injection block by `OpenClawChannelBackend.post()` at invocation time.

**Text archival:**
OpenClaw executes → generates output → POSTs to Qhorus channel endpoint via `deliver: "webhook"` →
`OversightGateService.evaluate()` dispatches a non-resolving STATUS message (no correlationId →
Qhorus `dispatch()` skips `commitmentService.acknowledge()` — purely archival). The STATUS records
the agent's text output for audit; the DONE from the tool call is the authoritative completion record.

**Multi-tenancy note — delivery webhook path:** Webhook callbacks arrive without a casehub
principal (`CurrentPrincipal = MockCurrentPrincipal = DEFAULT_TENANT_ID`). `OpenClawDeliveryResource`
resolves `tenancyId` by looking up the channel entity cross-tenant via
`@CrossTenant CrossTenantChannelStore.findById(channelId)` and passing it explicitly to
`evaluate()`. Do not use `ChannelService.findById()` (tenant-scoped — returns empty for
non-default tenants, causing silent 404 and OpenClaw retries). See protocol
`PP-20260612-520281`.

### 3.3 Read Path — Active (Qhorus to OpenClaw LLM)

When a COMMAND arrives on a Qhorus channel, `ChannelBackend.post()` (implemented by
casehub-openclaw) calls `POST /hooks/agent` with the COMMAND content. OpenClaw's LLM receives
it as a prompt and responds. This is event-driven, clean, and fits both systems' architectures.

### 3.4 Read Path — Passive (Observe Channel, Cross-Agent Awareness)

This does NOT work naturally. OpenClaw has no automatic awareness of other agents' channel
activity between heartbeat ticks. Addressed by the `ChannelContextWindow` service (see §5).

### 3.5 Speech Act Signaling (tool-call-first, openclaw#28)

Typed speech act signaling is the responsibility of MCP tools, not text classification:

| Signal | Tool call | Result |
|--------|-----------|--------|
| DONE (complete) | `casehub_done(agentId, commitmentId, outcome)` | DONE dispatched to Qhorus → commitment FULFILLED |
| DECLINE (cannot proceed) | `casehub_reject(agentId, commitmentId, reason)` | DECLINE dispatched → commitment DECLINED |
| HANDOFF (delegate) | `casehub_delegate(agentId, commitmentId, reason, toAgent)` | HANDOFF dispatched → commitment DELEGATED |
| STATUS (progress) | `casehub_checkpoint(agentId, commitmentId, note)` | STATUS dispatched → Watchdog TTL reset |

The `commitmentId` (= correlationId of the COMMAND commitment) is injected into the COMMAND message
by `OpenClawChannelBackend.post()` at invocation time — the agent receives it as a concrete value
and can call `casehub_done` directly without a prior `casehub_commit` lookup.

Text output via `deliver:webhook` is archived as a non-resolving STATUS message (no correlationId,
no commitment state change). The tool call is the sole completion signal.

### 3.6 ChannelBackend SPI as Bidirectional Bridge

`casehub-openclaw` implements the `ChannelBackend` SPI (same pattern as Claudony):
- Qhorus → OpenClaw: `ChannelBackend.post()` → `POST /hooks/agent` with message content
- OpenClaw → Qhorus: `deliver: webhook` → Qhorus channel endpoint → `MessageService.dispatch()`

The full normative loop applies to all OpenClaw agent communications: Commitment tracking,
Watchdog, speech act types, ledger. OpenClaw becomes a participant in the normative mesh,
not just an external execution runtime.

---

## 4. End-to-End Mesh Fit Assessment

### 4.1 Fundamental Mismatch

Qhorus assumes persistent channel participants continuously aware of what others post. OpenClaw
is episodic — discrete turns with no inherent inter-turn memory.

The active patterns (COMMAND/RESPONSE, oversight gates) are a strong fit. The passive patterns
(observe channel, cross-agent awareness) require ChannelContextWindow and are approximations
rather than true persistent subscription. This is a bounded, known limitation.

### 4.2 Pattern Assessment Table

| Pattern | Fit | Notes |
|---|---|---|
| COMMAND received → agent responds → DONE/DECLINE | ✅ Clean | `ChannelBackend.post()` → `/hooks/agent` → `deliver: webhook` |
| Human oversight gate | ✅ Clean | Same push model; OpenClaw delivers to WhatsApp/Telegram |
| Commitment tracking on all interactions | ✅ Clean | Qhorus owns this; no OpenClaw changes needed |
| Ledger records all agent communications | ✅ Clean | Qhorus owns this; no OpenClaw changes needed |
| Channel history context at turn start | ⚠️ Requires engineering | ChannelContextWindow + `before_prompt_build` injection |
| Observe channel passive watch | ⚠️ Approximation | Heartbeat + ChannelContextWindow injection; not real-time |
| Multi-agent channel awareness | ⚠️ Requires engineering | Cross-channel context injection via ChannelContextWindow |
| Continuous observation (true streaming) | ❌ Not natural | OpenClaw is episodic; approximated by heartbeat |

### 4.3 Mitigation for Time-Sensitive Coordination

For cases where sub-heartbeat coordination is needed: the direct call mode (`POST /hooks/agent`)
can be used — CaseHub fires the agent immediately when coordination is needed rather than waiting
for the next heartbeat tick. This does not solve true real-time streaming but resolves most
practical SLA-bounded scenarios.

---

## 5. ChannelContextWindow — Full Design

### 5.1 What It Is

A short-term, agent-scoped, TTL-evicting buffer of Qhorus channel activity — purpose-built to
bridge OpenClaw's episodic model with Qhorus's continuous channel mesh.

| Store | Lifespan | Content | Purpose |
|---|---|---|---|
| Ledger | Permanent | Tamper-evident event chain | Compliance audit |
| CaseMemoryStore | Indefinite | Semantic facts, entity relationships | Cross-case knowledge recall |
| ChannelContextWindow | Minutes/hours (TTL) | Raw channel messages, sliding window | LLM context injection at turn start |

These three stores serve different consumers and must not be conflated. ChannelContextWindow is
NOT in the critical path for correctness — commitments, Watchdog, and ledger are completely
unaffected by whether the cache works. A cache miss means the agent had less context for one
turn. It does not break the system.

### 5.2 Why It Is Necessary — Concrete Examples

Without cross-channel context injection, each OpenClaw agent operates in an information silo.
It knows only: (a) the single message that woke it, (b) what it itself did in prior turns via
its own memory files. It does not know what other agents are observing, the current household
or case state, or what another agent just reported.

**Example 1 — Grocery agent ignores a budget warning**

Without: finance-agent posted to the household observe channel 20 minutes ago: *"Monthly
discretionary budget exhausted — essentials only until month end."* grocery-agent's heartbeat
fires. COMMAND: run this week's shopping order. It executes the full regular shop — wine,
premium coffee, non-essentials. £180 charged. The budget warning was there. The grocery agent
was connected to the same mesh. But it had no idea.

With: grocery-agent wakes. `before_prompt_build` injects: *"finance-agent posted 20 min ago on
household/observe: discretionary budget exhausted, essentials only."* LLM switches to
essentials-only basket, posts STATUS explaining the adjustment, creates an oversight WorkItem
for any additions.

**Example 2 — Medical agent asks an irrelevant question**

Without: smart home sensors have been posting to health/observe all morning — patient was in the
kitchen at 11:02am. health-agent heartbeat fires at 11:05am. It sends a generic WhatsApp: *"Have
you taken your morning medication?"*

With: health-agent wakes. Hook injects the morning movement log. LLM sees the patient was in the
kitchen — where the medication is kept — 3 minutes ago. Response: *"I noticed you were in the
kitchen just now — did you take your morning tablets while you were there?"*

**Example 3 — Security finding missed by code review agent (enterprise)**

Without: security-agent posted two EVENTs to the case observe channel — credential pattern in
auth.java, hardcoded endpoint in config.py. code-review-agent's SLA fires. It completes its
structural review, posts DONE with quality assessment. No mention of the security findings. PR
approved.

With: code-review-agent wakes. Hook injects security-agent's findings. LLM escalates case
severity, creates a security-review WorkItem with specific file references, holds its own DONE
until the security WorkItem resolves. Additionally: the ledger shows code-review-agent had the
security findings in context at decision time.

**Example 4 — Travel agent books a conflicting trip**

Without: calendar-agent posted to household/observe at 9am: *"Work deadline Friday 5pm — Project
X deliverable."* travel-agent's booking case starts at 11am. COMMAND: book weekend flights
departing Friday evening. Books a 6pm departure. User misses the deadline.

With: travel-agent wakes. Hook injects calendar-agent's deadline notice. LLM flags the conflict
and routes to oversight for a human decision on the departure time.

### 5.3 Why Not Query the Ledger?

The ledger has all of this. But it is designed for tamper-evident audit, not LLM context assembly:

1. It stores a flat sequence of all lifecycle events across the entire platform — not
   pre-filtered by agent relevance
2. Querying for "recent channel messages relevant to this agent, formatted for a system prompt"
   requires significant transformation that is not the ledger's job
3. It has no concept of "what's relevant for agent X right now" — that is a routing concern,
   not a compliance concern
4. The ledger's Merkle chain is optimised for verification, not millisecond context retrieval
5. The pre-formatting, relevance filtering, and per-agent scoping needed are a separate
   service responsibility

The ChannelContextWindow is not storing new data. It presents existing Qhorus channel activity
in the right format, for the right consumer, at the right time.

### 5.4 The Complexity Justification

The implementation is not complex in absolute terms:

- A `MessageObserver` implementation: approximately 3–4 lines to register; passively receives
  all Qhorus messages at near-zero cost to the dispatch path
- A per-channel ring buffer: a standard data structure, configurable size and TTL
- A single REST endpoint: `GET /channel-context/{agentId}?since={sequenceNumber}`
- A Python SDK `before_prompt_build` hook: approximately 20 lines, fires before each agent turn

The alternative complexity: without this, every skill that needs cross-agent awareness must
explicitly call other agents' endpoints, maintain its own state, and handle its own staleness.
That produces N bespoke per-skill solutions instead of one shared infrastructure piece.

The visibility argument: a user who installs casehub-openclaw and sees grocery-agent ignore a
finance-agent budget warning will conclude the integration does not work. The cache is what
makes the multi-agent mesh visible and tangible. Without it, the normative layer is invisible —
commitments track correctly, ledger records faithfully, but agents behave as if isolated.
Correct infrastructure, broken user experience.

### 5.5 Technical Architecture

**MessageObserver SPI — collection layer:**

casehub-openclaw implements the `MessageObserver` SPI in casehub-qhorus. Receives every
dispatched message across all channels passively. Writes to per-channel ring buffers. The
observer must never throw — Qhorus fanOut to non-default backends is non-fatal by design.
Catch, log, increment metric, continue.

**Ring buffer — storage layer:**

Per-channel ring buffer of recent messages. Configurable:
- Max messages per channel (e.g., 100)
- TTL (e.g., 30 minutes)
- Drop policy on overflow: always keep newest, drop oldest

**REST endpoint — query layer:**

`GET /channel-context/{agentId}?since={windowSeq}`

Returns messages on channels associated with the agent since the specified cursor, structured
as JSON. `since` defaults to `0` (all buffered messages). Single call, pre-filtered; the SDK
formats for the system prompt.

**Multi-tenancy:** The endpoint uses `currentPrincipal.tenancyId()` internally — the
`agentId` alone is no longer unique across tenants. Internally, `ChannelContextWindowService`
keys on `AgentKey(agentId, tenancyId)`. In single-tenant deployments `MockCurrentPrincipal`
returns `DEFAULT_TENANT_ID` — correct behaviour. In multi-tenant deployments, the Python SDK
and TypeScript plugin must carry a casehub principal (via auth retrofit, openclaw#33) for
the correct tenant window to be returned.

**`since` cursor — internal `windowSeq`, not Qhorus `sequenceNumber`:**

`MessageReceivedEvent` carries no `sequenceNumber` field. Qhorus's internal `sequenceNumber`
is per-channel (not global) — two messages on different channels can both have `sequenceNumber = 1`,
making it ambiguous as a cross-channel cursor. `ChannelContextWindow` assigns its own global
monotonic `windowSeq` (via `AtomicLong`) to every ingested message. The `since` parameter
references this internal sequence. See design spec §5 for the full cursor design and restart
reset detection via `currentWindowSeq`.

**Python SDK hook — injection layer:**

Overflow notice and message context are **additive** — both must be injected when eviction
has occurred but messages still remain in the buffer. The SDK injects all relevant signals
independently:

```python
@agent.on("before_prompt_build")
def inject_channel_context(ctx):
    response = cache_client.get(
        agent_id=ctx.agent_id,
        since=ctx.last_window_seq
    )
    if not response.agent_has_association:
        return {}

    # Detect service restart: counter reset below our cursor
    if ctx.last_window_seq > response.current_window_seq:
        ctx.last_window_seq = 0
        return {}  # skip this turn; next turn calls with since=0

    parts = []

    # Overflow notice (additive — independent of whether messages exist)
    if response.last_eviction_window_seq > ctx.last_window_seq:
        parts.append("Note: Some messages evicted (high volume). Full history in ledger.")

    # Available messages (always injected if present)
    if response.messages:
        parts.append(format_channel_messages(response.messages))

    # Idle notice only when no messages AND no relevant eviction
    if not response.messages and response.last_eviction_window_seq <= ctx.last_window_seq:
        parts.append(f"No channel activity in the last {ttl_minutes} minutes.")

    ctx.last_window_seq = response.last_window_seq
    return {"appendSystemContext": "\n\n".join(parts)} if parts else {}
```

`appendSystemContext` lands in the system prompt — rebuilt every turn, never compacted. This
is the compaction-safe injection point.

**Cross-channel awareness:**

The endpoint supports optional cross-channel context: messages from observe channels that the
agent is registered to watch, not only channels it owns. This enables home-agent to see
finance-agent's recent observe channel posts without subscribing to every channel individually.

### 5.6 Reliability and Failure Modes

**Two-layer reliability contract:**

| Layer | Mechanism | Reliability guarantee |
|---|---|---|
| Correctness | Qhorus (commitments, Watchdog, ledger) | Reliable — not affected by cache |
| Intelligence | ChannelContextWindow | Best-effort — graceful degradation |

**Failure mode: ring buffer overflow**

If message volume is high or heartbeat interval is long, the buffer fills and older messages are
evicted before the agent wakes. Configurable buffer size; metric on overflow frequency. When
overflow occurs, inject an explicit signal: *"Note: N messages not retained (high volume). Full
history available in ledger."* The LLM knows it has a partial view. Never return silently empty.

**Failure mode: TTL expiry before agent wakes**

If an agent is dormant beyond the TTL, messages expire. An empty window must never return
silently — inject elapsed time since last activity: *"No channel activity in the last N
minute(s)."* If `lastChannelActivity` is the epoch sentinel (`1970-01-01T00:00:00Z`), inject:
*"No channel activity recorded for this agent yet."* Absence of activity is itself informative.
The idle notice is computed from `lastChannelActivity` in the REST response, not from the
configured TTL value — actual elapsed time is more informative than a static config label.

**Failure mode: cache service unavailable**

The REST endpoint is down when the Python SDK hook fires. Fail open — agent proceeds without
context injection. Turn still completes; the COMMAND is still processed. Log and alert on
repeated failures; never break the agent turn.

**Failure mode: MessageObserver write failure**

Observer fires but cache write throws. Catch, log, increment metric — never propagate back to
Qhorus. The message is in the ledger; the only loss is the cache copy.

**Failure mode: multiple OpenClaw instances**

The Python SDK's `last_window_seq` cursor is per-process state. Multiple OpenClaw instances
running the same agent have independent cursors. Options: sticky routing (each agentId always
hits the same instance), shared cursor store, or accept per-instance cursor drift with slight
risk of re-delivering messages. Consequence: redundant context, not missing context.

### 5.7 Design Rules

1. Never fail a Qhorus fanOut because of cache write failure
2. Always signal partial views — overflow and TTL expiry inject explicit notices, not silent
   empty windows
3. Use internal `windowSeq` as the `since` cursor — not wall-clock timestamp, not Qhorus per-channel `sequenceNumber`
4. Fail open on cache unavailability — agent turn continues with less context
5. Buffer size and TTL are deployment configuration — tuned to heartbeat interval and expected
   message volume
6. Alert on repeated failures — in health monitoring, not the critical alert path
