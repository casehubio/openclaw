# Examples Design — casehub-openclaw

**Date:** 2026-06-16
**Status:** Final (iteration 8 — post-review-7)
**Scope:** Three self-contained runnable demos in a new `examples/` directory
**Goal:** Drive traffic from OpenClaw's existing tutorial communities by building on canonical popular use cases, demonstrating what CaseHub adds to each

---

## 1. Problem Statement

casehub-openclaw has no runnable examples. Engineers and PMs evaluating CaseHub cannot see it working end-to-end without significant setup effort. The OpenClaw community has a large and active tutorial ecosystem — multi-agent team setups, trading bots, overnight ops agents — but none of these tutorials address what happens when agents do consequential things without accountability infrastructure underneath them.

The examples directory targets three distinct OpenClaw communities, each with canonical high-traffic tutorials:

| Example | Community | Reference tutorial |
|---------|-----------|-------------------|
| `multi-agent-dev-team/` | GitHub / developer tooling | ["My Multi-Agent Dev Team using OpenClaw"](https://www.youtube.com/watch?v=y-GjRMHfTaU) |
| `trading-oversight/` | Finance / trading bots | [OpenClaw for Trading: Complete 2026 Guide](https://openclawforge.com/blog/openclaw-for-trading-complete-2026-guide-automated-trading-ai-agents/) |
| `incident-response/` | SRE / ops | ["How I Run 8 AI Employees 24/7 with OpenClaw"](https://www.youtube.com/watch?v=CYBZmwOmsk8) |

Each example README explicitly references its source material: *"Built on the pattern from [title]. Here's what changes when you add CaseHub."* This drives inbound traffic from readers of those tutorials searching for the next step.

---

## 2. Design Principles

**Real LLM required.** Each example calls the real OpenClaw gateway, which calls Claude via the Anthropic API. An `ANTHROPIC_API_KEY` is required — set it in `.env` before running. This is a first-class prerequisite, not a footnote. The "wow moment" is a real agent reasoning through a real problem and being stopped at a consequential action — that requires live inference, not a script.

**One gate per demo.** Each example has exactly one oversight gate — the consequential action. `DemoGateClassifier` (Section 4.2) gates on a configured agentId, not on outcome text. Gating on `action.workerId()` is stable because `buildPrompt()` in `OpenClawChannelBackend.post()` explicitly injects `casehub_done("{agentId}", "{commitmentId}", outcome)` into every COMMAND message — the agent uses this injected value, not open-ended reasoning. `agentId` IS a `@ToolArg` written by the LLM, but it is written from explicitly provided context rather than derived from scratch. Analysis agents have different agentIds than the action agent; only the action agent's `casehub_done` call fires the gate.

**Runnable in under five minutes.** Each example starts with `docker compose up` in the example directory, followed by `./scenario.sh`. No external accounts beyond an Anthropic API key.

**Full integration path, not a stub.** The oversight gate fires only for channel-backed commitments. `CommitmentTools.selfCommit_done()` calls `commitmentService.fulfill()` directly — no gate. Without real Qhorus channels, the demo fails silently. Every example uses real Qhorus channels.

**Mock external services, real CaseHub integration.** GitHub, broker APIs, and monitoring systems are simulated. Qhorus channels, commitment lifecycle, oversight gate, MCP tools, and audit trail are all real.

**ExampleController in `app/` behind a runtime flag.** No new Maven module — demo code goes in the existing `app/` module. A `@Path @ApplicationScoped` bean is always registered by RESTEasy regardless of any `@ConfigProperty` — the endpoint exists in all deployments. The handler checks `casehub.example.enabled` at runtime and returns `503 Service Unavailable` when false. `DemoGateClassifier` is always a registered CDI bean; when `CASEHUB_EXAMPLE_GATE_AGENTID` is blank (default), every `classify()` call returns `Autonomous` — completely inert.

**Fixed caseId per example.** ExampleController uses a UUID constant per example (not a fresh UUID per run). This makes `openChannel()` idempotent and allows `ChannelContextWindowService` ring buffers to accumulate across runs within the same JVM session — enabling the ChannelContextWindow demonstration in the incident-response example.

---

## 3. Repository Structure

```
examples/
├── README.md                          # Overview, capability matrix, quick start
├── docker-compose.base.yml            # Shared services: postgres, openclaw-gateway
├── multi-agent-dev-team/
│   ├── README.md                      # Narrative, reference, before/after
│   ├── docker-compose.yml             # extends base + mock-github + mock-ci;
│   │                                  # re-declares depends_on (not propagated by extends)
│   ├── .env.example                   # ANTHROPIC_API_KEY, CASEHUB_EXAMPLE_GATE_AGENTID=reviewer
│   ├── agents/                        # Per-agent system prompt files (system-prompt.md)
│   │   ├── planner/system-prompt.md
│   │   ├── coder/system-prompt.md
│   │   └── reviewer/system-prompt.md
│   ├── skills/
│   │   └── casehub-example.md         # gated:true response handling (Section 5.5)
│   ├── mocks/
│   │   ├── github_mock.py
│   │   └── ci_mock.py
│   ├── scenario.sh
│   └── approve.sh
├── trading-oversight/
│   ├── README.md
│   ├── docker-compose.yml             # extends base + mock-broker + mock-feed;
│   │                                  # re-declares depends_on
│   ├── .env.example                   # ANTHROPIC_API_KEY, CASEHUB_EXAMPLE_GATE_AGENTID=execution
│   ├── agents/
│   │   ├── signal/system-prompt.md
│   │   ├── risk/system-prompt.md
│   │   └── execution/system-prompt.md
│   ├── skills/
│   │   └── casehub-example.md
│   ├── mocks/
│   │   ├── broker_mock.py
│   │   └── feed_mock.py
│   ├── scenario.sh
│   └── approve.sh
└── incident-response/
    ├── README.md
    ├── docker-compose.yml             # extends base + mock-logs + mock-config;
    │                                  # re-declares depends_on
    ├── .env.example                   # ANTHROPIC_API_KEY, CASEHUB_EXAMPLE_GATE_AGENTID=resolver
    ├── agents/
    │   ├── investigator/system-prompt.md
    │   └── resolver/system-prompt.md
    ├── skills/
    │   └── casehub-example.md
    ├── mocks/
    │   ├── log_mock.py
    │   └── config_mock.py
    ├── scenario.sh
    └── approve.sh
```

### Agent configuration: system-prompt.md and SKILL.md

Each agent needs two kinds of configuration:

- **`system-prompt.md`** (in `agents/{role}/`) — the per-agent persona and instructions, configured in the OpenClaw gateway as the agent's system prompt. Defines role-specific behaviour (what a Planner does vs. what a Coder does). The specific OpenClaw mechanism for loading this (admin UI, config file, or API) must be confirmed against the OpenClaw gateway documentation when implementing.
- **`skills/casehub-example.md`** — a SKILL.md file that extends `casehub-global` and adds the `gated: true` response handling (Section 5.5) required for the oversight gate to work. This is loaded by the OpenClaw gateway alongside the production CaseHub skills.

### `docker-compose.base.yml`

```yaml
services:
  postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: casehub
      POSTGRES_USER: casehub
      POSTGRES_PASSWORD: casehub
    ports: ["5432:5432"]

  openclaw-gateway:
    image: openclaw/gateway:latest
    environment:
      ANTHROPIC_API_KEY: ${ANTHROPIC_API_KEY}
    ports: ["3000:3000"]

  casehub-openclaw:
    build:
      context: ../..
      dockerfile: app/src/main/docker/Dockerfile.jvm
    environment:
      QUARKUS_DATASOURCE_JDBC_URL: jdbc:postgresql://postgres:5432/casehub
      OPENCLAW_HOOK_BASE_URL: http://openclaw-gateway:3000
      CASEHUB_EXAMPLE_ENABLED: "true"
      CASEHUB_EXAMPLE_TENANCYID: "demo"         # no-hyphen property: casehub.example.tenancyid
      CASEHUB_EXAMPLE_GATE_AGENTID: ${CASEHUB_EXAMPLE_GATE_AGENTID}  # casehub.example.gate.agentid
      # Agent config — one entry per demo agent.
      # SmallRye Config env var rules: _ → .  |  __ → -
      # session-key contains a hyphen → must use SESSION__KEY (double underscore)
      # agentId names are hyphen-free → single underscore throughout
      # Per-example agents declared in each example's docker-compose.yml (not here).
    ports: ["8080:8080"]
```

Each example's `docker-compose.yml` extends the base and adds mock services. It **must re-declare `depends_on`** on the `casehub-openclaw` block — Docker Compose `extends` does not propagate `depends_on`:

```yaml
# example: multi-agent-dev-team/docker-compose.yml
services:
  casehub-openclaw:
    extends:
      file: ../docker-compose.base.yml
      service: casehub-openclaw
    depends_on: [postgres, openclaw-gateway, mock-github, mock-ci]  # must be here, not inherited
    environment:
      CASEHUB_EXAMPLE_GATE_AGENTID: reviewer
      # Agent config (agentId keys are hyphen-free → single underscore throughout).
      # session-key has a hyphen → SESSION__KEY (double underscore; SmallRye maps __ → -).
      CASEHUB_OPENCLAW_AGENTS_PLANNER_SESSION__KEY: planner
      CASEHUB_OPENCLAW_AGENTS_PLANNER_CAPABILITIES: planning
      CASEHUB_OPENCLAW_AGENTS_CODER_SESSION__KEY: coder
      CASEHUB_OPENCLAW_AGENTS_CODER_CAPABILITIES: coding
      CASEHUB_OPENCLAW_AGENTS_REVIEWER_SESSION__KEY: reviewer
      CASEHUB_OPENCLAW_AGENTS_REVIEWER_CAPABILITIES: code-review

  mock-github:
    build: ./mocks
    command: python github_mock.py
    ports: ["5001:5001"]

  mock-ci:
    build: ./mocks
    command: python ci_mock.py
    ports: ["5002:5002"]

  postgres:
    extends:
      file: ../docker-compose.base.yml
      service: postgres

  openclaw-gateway:
    extends:
      file: ../docker-compose.base.yml
      service: openclaw-gateway
    depends_on: []

# trading-oversight/docker-compose.yml adds:
#   CASEHUB_EXAMPLE_GATE_AGENTID: execution
#   CASEHUB_OPENCLAW_AGENTS_SIGNAL_SESSION__KEY: signal / CAPABILITIES: market-analysis
#   CASEHUB_OPENCLAW_AGENTS_RISK_SESSION__KEY: risk / CAPABILITIES: risk-assessment
#   CASEHUB_OPENCLAW_AGENTS_EXECUTION_SESSION__KEY: execution / CAPABILITIES: trade-execution
#
# incident-response/docker-compose.yml adds:
#   CASEHUB_EXAMPLE_GATE_AGENTID: resolver
#   CASEHUB_OPENCLAW_AGENTS_INVESTIGATOR_SESSION__KEY: investigator / CAPABILITIES: incident-investigation
#   CASEHUB_OPENCLAW_AGENTS_RESOLVER_SESSION__KEY: resolver / CAPABILITIES: incident-resolution
```

---

## 4. ExampleController and Supporting Components

### 4.1 ExampleController

`ExampleController` is a JAX-RS resource in `app/` — not a separate Maven module. It is gated by `casehub.example.enabled` (default false) so it is inert in production builds. All demo-specific code stays in `app/`, which is the right call: no build plumbing, no separate Quarkus app, no maintained pom.xml split.

```java
@POST
@Path("/{exampleId}/start")
@Blocking   // required: app uses quarkus-rest (RESTEasy Reactive); handler runs on event loop
            // by default. Thread.sleep() in the polling loop would block the event loop,
            // preventing approve.sh (POST /openclaw/delivery/oversight/{gateId}) from being
            // processed — demo permanently stuck. @Blocking moves handler to a worker thread.
public Response start(@PathParam("exampleId") String exampleId) { ... }
```

`{exampleId}` is one of `multi-agent-dev-team`, `trading-oversight`, `incident-response`.

**On invocation, for each step:**

1. **Establish channels** — call `caseChannelProvider.openChannel(DEMO_CASE_ID, "work")`, then `"observe"`, then `"oversight"`. `DEMO_CASE_ID` is a UUID constant per example (e.g., `UUID.fromString("00000001-0000-0000-0000-000000000001")` for dev-team). `openChannel()` is idempotent (finds existing channel by name); it also calls `contextService.bindChannel()` internally.

2. **Register agent** — call `registry.register(agentId, tenancyId, caseId, sessionKey)` and `contextService.bindAgent(agentId, caseId)`. This mirrors what `OpenClawWorkerProvisioner.provision()` does in production. `tenancyId` comes from `casehub.example.tenancyid` config (default `"demo"`). `sessionKey` comes from `casehub.openclaw.agents.{agentId}.session-key` config — the same agent config used in production.

3. **Dispatch COMMAND** — ExampleController generates `correlationId = UUID.randomUUID().toString()` before calling `ExampleSetup.setupAndDispatch()`. The method signature accepts `correlationId` as a parameter. The base `commandContent` does NOT need to embed the commitmentId — `buildPrompt()` in `OpenClawChannelBackend.post()` automatically appends the full CaseHub commitment context block (including `commitmentId: {correlationId}` and `casehub_done("{agentId}", "{correlationId}", outcome)`) when `correlationId != null` in the dispatched message. ExampleController builds simple base content; `buildPrompt()` handles injection. Qhorus's `ChannelGateway.fanOut()` calls `OpenClawChannelBackend.post()`, which calls `POST /hooks/agent`. **ExampleController never calls `OpenClawChannelBackend.post()` directly.**

4. **Poll for completion** — ExampleController submits a polling task to Quarkus `ManagedExecutor` that repeatedly calls `examplePoller.checkState(correlationId)` (a `@Transactional` CDI call — proper JPA context). Polling interval: 2 seconds. Stop when `state != null && state.isTerminal()`. Timeout: `CASEHUB_EXAMPLE_TIMEOUT_SECONDS` (default 300) — log and stop if exceeded.

5. **Handoff to next agent** — on terminal state:
   - `FULFILLED` → next agent configured: go to step 2 with the next agentId
   - `FULFILLED` → no next agent: print case summary, stop
   - `DECLINED` → log "human rejected — no further action"; stop
   - `DELEGATED` → log "agent escalated to {target}; commitment transferred; operator takes over"; stop — **do NOT dispatch the next agent**
   - Other terminal (FAILED, EXPIRED) → log error state; stop

6. **Print summary** — on all agents complete, print the case caseId so the caller can retrieve the audit trail via `GET /channel-context/{agentId}`.

### 4.2 DemoGateClassifier

Gates on `action.workerId()` (the agentId) — not on outcome text. `agentId` IS a `@ToolArg` — the LLM writes it as the first argument of the `casehub_done` tool call. However, it is highly stable in practice because `buildPrompt()` in `OpenClawChannelBackend.post()` explicitly injects the correct value into the COMMAND message: `casehub_done("{agentId}", "{commitmentId}", outcome)`. The agent uses this injected text rather than deriving the agentId from reasoning. This is "stable due to explicit context injection" — not "LLM is not involved."

`PlannedAction` is a record: `(String workerId, UUID caseId, String description, String actionType, Map<String, Object> context)`. `description` is the outcome text; `workerId` is the agentId. Keyword matching on `description` is fragile — "hot-patch", "applying the patch", "configuration patch" all collide with the keyword "patch". agentId matching has no such collisions.

```java
@ApplicationScoped
@RiskClassifier
public class DemoGateClassifier implements ActionRiskClassifier {

    @ConfigProperty(name = "casehub.example.gate.agentid", defaultValue = "")
    String gateAgentId;

    @Override
    public RiskDecision classify(PlannedAction action) {
        if (gateAgentId.isBlank()) return new RiskDecision.Autonomous();
        if (!gateAgentId.equalsIgnoreCase(action.workerId())) {
            return new RiskDecision.Autonomous();
        }
        return new RiskDecision.GateRequired(
            "Demo gate — agent '" + action.workerId() + "' requires oversight approval",
            true, null, null, null);
    }
}
```

Each example sets `CASEHUB_EXAMPLE_GATE_AGENTID` in its docker-compose environment:
- `multi-agent-dev-team`: `reviewer`
- `trading-oversight`: `execution`
- `incident-response`: `resolver`

Analysis agents call `casehub_done` with their own agentIds (planner, coder, signal, risk, investigator) → no match → Autonomous → no gate. One gate per demo.

### 4.3 ExampleSetup (setup phase — @Transactional)

`openChannel()` calls `channelService.create()` and `gateway.initChannel()` — JPA writes. `messageService.dispatch()` — JPA write. A JAX-RS handler is not automatically `@Transactional` in Quarkus. Extract the setup phase to a dedicated bean:

```java
@ApplicationScoped
public class ExampleSetup {

    @Inject OpenClawCaseChannelProvider caseChannelProvider;
    @Inject OpenClawAgentRegistry registry;
    @Inject ChannelContextWindowService contextService;
    @Inject MessageService messageService;

    // correlationId is generated by ExampleController BEFORE calling this method,
    // so the caller can poll on it. commandContent is base task description only —
    // buildPrompt() in OpenClawChannelBackend.post() automatically appends the full
    // CaseHub commitment context block (commitmentId, casehub_done invocation) when
    // correlationId is non-null. Do NOT embed commitmentId in commandContent.
    @Transactional
    public void setupAndDispatch(UUID caseId, String tenancyId,
                                  String agentId, String sessionKey,
                                  String correlationId,      // caller-generated; passed through to dispatch
                                  String commandContent) {   // base content only — no commitmentId
        caseChannelProvider.openChannel(caseId, "work");
        caseChannelProvider.openChannel(caseId, "observe");
        caseChannelProvider.openChannel(caseId, "oversight");
        registry.register(agentId, tenancyId, caseId, sessionKey);
        contextService.bindAgent(agentId, caseId);
        UUID workChannelId = caseChannelProvider.listChannels(caseId).stream()
            .filter(c -> "work".equals(c.purpose()))
            .map(c -> UUID.fromString(c.id()))
            .findFirst().orElseThrow();
        messageService.dispatch(MessageDispatch.builder()
            .channelId(workChannelId).sender("example-controller")
            .type(MessageType.COMMAND).content(commandContent)
            .correlationId(correlationId)   // buildPrompt() sees this and injects into agent prompt
            .actorType(ActorType.SYSTEM).build());
    }
}
```

For successive agents in the same run, ExampleController calls `setupAndDispatch()` again with the new agentId. `openChannel()` is idempotent — channels already exist, only `registry.register()` and `bindAgent()` update. Each call runs in its own transaction.

### 4.4 ExamplePoller (polling phase — @Transactional)

```java
@ApplicationScoped
public class ExamplePoller {

    @Inject CommitmentStore commitmentStore;

    @Transactional
    public CommitmentState checkState(String correlationId) {
        return commitmentStore.findByCorrelationId(correlationId)
            .map(c -> c.state)
            .orElse(null);   // null = commitment not yet visible in JPA (keep polling)
    }
}
```

**Polling contract:** `null` means the commitment is not yet visible — race between the COMMAND dispatch transaction committing and the JPA query. Continue polling. Stop when `state != null && state.isTerminal()`. Never call `state.isTerminal()` on a null value — NPE.

Terminal states (from `CommitmentState.isTerminal()`): FULFILLED, DECLINED, FAILED, DELEGATED, EXPIRED. Non-terminal: OPEN, ACKNOWLEDGED.

**After `casehub_escalate`:** HANDOFF is dispatched → Qhorus transitions the commitment to DELEGATED. DELEGATED is terminal. `ExamplePoller.checkState()` returns DELEGATED. The polling loop exits normally — NOT via timeout. ExampleController's handoff logic (Section 4.1 step 5) handles DELEGATED as "escalated, stop" — it does NOT dispatch the next agent.

With `@Blocking`, the `start()` handler runs on a Quarkus worker thread throughout its entire lifetime — the polling loop runs directly on that thread. No `ManagedExecutor` needed. Polling interval: 2 seconds. Timeout: `CASEHUB_EXAMPLE_TIMEOUT_SECONDS` (default 300).

ExampleController caller pattern (runs on worker thread — blocking calls are safe):
```java
String correlationId = UUID.randomUUID().toString();
exampleSetup.setupAndDispatch(caseId, tenancyId, agentId, sessionKey,
    correlationId, "You are the Planner. Review issue #42.");
// poll on the same correlationId:
CommitmentState state = null;
while (state == null || !state.isTerminal()) {
    Thread.sleep(2000);
    state = examplePoller.checkState(correlationId);
}
// state is now FULFILLED, DECLINED, DELEGATED, FAILED, or EXPIRED
```

### 4.5 approve.sh pattern

```bash
#!/usr/bin/env bash
# Gate ID is in the Quarkus log:
# INFO [OversightGateService] Gate opened: gateId=<uuid> agentId=... commitmentId=... caseId=... reason=...
GATE_ID=$(docker compose logs casehub-openclaw 2>/dev/null \
  | grep "Gate opened:" \
  | tail -1 \
  | grep -oE 'gateId=[^ ]+' \
  | cut -d= -f2)

if [ -z "$GATE_ID" ]; then
  echo "No open gate found. Has the scenario reached the consequential action?"
  exit 1
fi

DECISION="${1:-approved}"   # ./approve.sh         → approved
                            # ./approve.sh reject   → rejected

curl -s -X POST "http://localhost:8080/openclaw/delivery/oversight/${GATE_ID}" \
  -H "Content-Type: application/json" \
  -d "{\"output\": \"${DECISION}\"}" \
  | python3 -m json.tool
```

The `output` field maps to `OpenClawOversightDeliveryPayload.output()`. `parseApproval()` checks whether the first token equals "approved". Sending `{"decision":"approved"}` would be silently rejected (wrong field name).

`GET /openclaw/plugin/commitments/{agentId}` is **not used** for gate ID discovery — that endpoint filters by `c.obligor == agentId`. Gate COMMANDs are dispatched with `sender = "openclaw-gate"` as obligor; they are invisible to the agent-scoped query.

---

## 5. Example 1: `multi-agent-dev-team/`

### Narrative

*The canonical tutorial shows a developer setting up a team of OpenClaw agents to handle their coding backlog overnight. This example runs the same team with CaseHub underneath it: every commitment tracked, one deployment gate before anything merges.*

### Demo caseId

`UUID.fromString("00000001-0000-0000-0000-000000000001")`

### Agents

| Agent | Role | Gates? | CaseHub tools |
|-------|------|---------------------|---------------|
| Planner | Reads issue, creates subtask records | No (agentId="planner" ≠ "reviewer") | `casehub_done`, `casehub_create_workitem` |
| Coder | Implements fix, writes tests | No (agentId="coder" ≠ "reviewer") | `casehub_checkpoint`, `casehub_done` |
| Reviewer | Reviews diff, approves merge | **Yes** (agentId="reviewer" == "reviewer") | `casehub_done`, `casehub_reject` |

`casehub_commit` is not called. Agents receive `commitmentId` in the COMMAND message and call `casehub_done` directly (per the tool description: "For case steps, commitmentId is provided in the COMMAND message — call casehub_done directly").

### casehub_create_workitem — capability demonstration only

The Planner calls `casehub_create_workitem` to create subtask records. These dispatch to `work/` channels (prefix `"work/"`). `OpenClawChannelBackend` registers only for channels starting with `"case-"` — work-channel COMMANDs are invisible to the OpenClaw backend. The subtasks created have **no causal effect on agent sequencing in this demo**. ExampleController sequences the agents directly.

This is stated explicitly in the Planner's `system-prompt.md` and in the example README: "casehub_create_workitem is shown here to demonstrate the work-queue capability; in this demo, the ExampleController sequences agents directly rather than via the work queue."

### Flow

```
1. scenario.sh → POST /example/multi-agent-dev-team/start
   ExampleController:
     openChannel(DEMO_CASE_ID, "work")   → channel: case-00000001-...0001/work
     openChannel(DEMO_CASE_ID, "observe") → ...
     openChannel(DEMO_CASE_ID, "oversight") → ...
     registry.register("planner", "demo", DEMO_CASE_ID, "planner-session-key")
     contextService.bindAgent("planner", DEMO_CASE_ID)
     messageService.dispatch(COMMAND, "You are the Planner. Review GitHub issue #42.")
     → Qhorus fanOut() → OpenClawChannelBackend.post() → buildPrompt() appends commitment
       context block (commitmentId, casehub_done invocation) → POST /hooks/agent → Planner runs

2. Planner:
   GET /repos/demo/app/issues/42 (mock) → fixture
   casehub_create_workitem(...) [capability demo — no effect on flow]
   casehub_done("planner", "<uuid-1>", "Tasks logged: fix null check, add test")
   → DemoGateClassifier: workerId="planner" ≠ "reviewer" → Autonomous
   → DONE dispatched → commitment FULFILLED

3. ExampleController detects FULFILLED via ExamplePoller
   → registry.register("coder", "demo", DEMO_CASE_ID, "coder-session-key")
   → contextService.bindAgent("coder", DEMO_CASE_ID)
   → messageService.dispatch(COMMAND, "You are the Coder. Fix null check in PaymentService.")
   → Coder runs

4. Coder:
   GET /repos/demo/app/contents/PaymentService.java (mock) → fixture
   casehub_checkpoint("<uuid-2>", "Writing fix")    ← Watchdog reset
   POST /ci/runs (mock) → {"status": "passed", "tests": 47}
   casehub_done("coder", "<uuid-2>", "Fix complete, 47 tests pass")
   → DemoGateClassifier: workerId="coder" ≠ "reviewer" → Autonomous → DONE → FULFILLED

5. ExampleController detects FULFILLED
   → registry.register("reviewer", "demo", DEMO_CASE_ID, "reviewer-session-key")
   → contextService.bindAgent("reviewer", DEMO_CASE_ID)
   → messageService.dispatch(COMMAND, "You are the Reviewer. Review diff for PaymentService.")
   → Reviewer runs

6. Reviewer:
   GET /repos/demo/app/pulls/1/files (mock) → fixture diff
   casehub_done("reviewer", "<uuid-3>", "Review passed — ready to merge")
   → DemoGateClassifier: workerId="reviewer" == "reviewer" → GateRequired
   → CommitmentTools.channelBacked_done() returns {"gated": true, "gateId": "..."}
   → casehub-example.md SKILL: surface pendingReason, end turn

7. OVERSIGHT GATE (wow moment):
   Quarkus log: Gate opened: gateId=<gate-uuid> agentId=reviewer ...
   approve.sh → POST /openclaw/delivery/oversight/<gate-uuid> {"output": "approved"}
   → fulfill() → DONE dispatched → Reviewer FULFILLED

8. ExampleController detects FULFILLED, no next agent
   → PUT /repos/demo/app/pulls/1/merge (mock) → {"merged": true}
   → prints case summary and audit trail location
```

### `gated: true` handling in casehub-example.md

```markdown
If casehub_done returns {"gated": true, "gateId": "...", "pendingReason": "..."}:
  Do NOT call casehub_done again.
  Do NOT consider the task complete.
  Surface the pendingReason if present.
  End your turn. The commitment will be closed by the human reviewer.
```

### Mock services

- **mock-github**: `GET /repos/demo/app/issues/42`, `GET /repos/demo/app/contents/PaymentService.java`, `GET /repos/demo/app/pulls/1/files`, `PUT /repos/demo/app/pulls/1/merge` (PR merge is `PUT /pulls/{n}/merge`, not `POST /merges` which is branch-merge)
- **mock-ci**: `POST /ci/runs` → `{"status": "passed", "tests": 47}`

### CaseHub capabilities demonstrated

| Capability | Shown |
|---|---|
| Channel-backed commitment lifecycle | ✅ |
| Multi-agent handoff via ExampleController | ✅ |
| `casehub_checkpoint` Watchdog reset | ✅ (Coder) |
| `casehub_create_workitem` (capability demo) | ✅ (Planner — noted as non-causal) |
| Single oversight gate on merge | ✅ |
| Audit trail | ✅ |

---

## 6. Example 2: `trading-oversight/`

### Narrative

*OpenClaw trading bots execute thousands of trades automatically. This example runs the signal → risk → execution pipeline with one difference: the execution agent asks before it acts.*

### Demo caseId

`UUID.fromString("00000002-0000-0000-0000-000000000002")`

### Agents

| Agent | Role | Gates? | CaseHub tools |
|-------|------|---------------------|---------------|
| Signal | Analyses market feed, confirms opportunity | No (agentId="signal" ≠ "execution") | `casehub_done` |
| Risk | Assesses exposure, correlation, daily limit | No (agentId="risk" ≠ "execution") | `casehub_done`, `casehub_reject` |
| Execution | Ready to place order | **Yes** (agentId="execution" == "execution") | `casehub_done` |

### Flow

```
1. scenario.sh → POST /example/trading-oversight/start
   ExampleController creates channels, registers Signal, dispatches COMMAND:
     "You are the Signal agent. Analyse NVDA market feed."

2. Signal:
   GET /feed/NVDA (mock WebSocket) → price history + momentum 0.84
   casehub_done("signal", "<uuid-1>", "BUY signal confirmed: NVDA entry $892, momentum 0.84")
   → DemoGateClassifier: workerId="signal" ≠ "execution" → Autonomous → DONE → FULFILLED

3. ExampleController registers Risk, dispatches:
   "You are the Risk agent. Assess: BUY 100 NVDA @ $892."

4. Risk:
   GET /broker/portfolio (mock) → current positions
   casehub_done("risk", "<uuid-2>",
     "MEDIUM risk: $89,200 exposure, daily headroom $4,300, stop-loss $871, no correlation conflict")
   → DemoGateClassifier: workerId="risk" ≠ "execution" → Autonomous → DONE → FULFILLED

5. ExampleController registers Execution, dispatches:
   "You are the Execution agent. Signal: BUY NVDA @ $892. Risk: MEDIUM. Place the order."

6. Execution:
   casehub_done("execution", "<uuid-3>",
     "Ready to place market order: BUY 100 NVDA @ $892, exposure $89,200")
   → DemoGateClassifier: workerId="execution" == "execution" → GateRequired
   → {"gated": true, "gateId": "..."} → SKILL: surface pendingReason, end turn

7. OVERSIGHT GATE (wow moment):
   "Gate opened: gateId=<uuid> reason=Demo gate — agent 'execution' requires oversight approval"
   Terminal display (formatted by approve.sh output):
     Action: BUY 100 NVDA market order | Exposure: $89,200 | Risk: MEDIUM
     Stop-loss: $871 | Daily headroom: $4,300
   approve.sh → {"output": "approved"}
   → fulfill() → DONE → FULFILLED
   → ExampleController: POST /broker/orders → {"filled": true, "price": 891.73}

   OR: approve.sh reject → {"output": "rejected"}
   → fulfill() dispatches DECLINE → commitment DECLINED
   → ExampleController: logs rejection — no order placed
   (The Execution agent does not run again; no casehub_reject is called.
    fulfill() dispatches DECLINE directly — the agent has already terminated its turn.)
```

### Mock services

- **mock-broker**: `GET /broker/portfolio` (current positions), `POST /broker/orders` (returns fill with slight slippage)
- **mock-feed**: Python WebSocket server streaming OHLCV fixture

### CaseHub capabilities demonstrated

| Capability | Shown |
|---|---|
| Channel-backed commitment lifecycle | ✅ |
| Multi-agent sequential pipeline | ✅ |
| Single gate before irreversible financial action | ✅ |
| Approve path | ✅ |
| Reject path (DECLINE via fulfill, not casehub_reject) | ✅ |
| Audit trail | ✅ |

---

## 7. Example 3: `incident-response/`

### Narrative

*The "8 AI employees 24/7" pattern runs agents overnight — great for low-stakes work. Ops teams can't give agents autonomous production access. This example shows the overnight investigation pattern with one gate before anything touches production.*

### Demo caseId

`UUID.fromString("00000003-0000-0000-0000-000000000003")`

Fixed caseId enables ChannelContextWindow accumulation: running scenario.sh a second time in the same JVM session gives the Investigator prior channel context injected at turn start (`appendSystemContext`). The buffers are in-memory — they reset if Quarkus restarts.

### Agents

| Agent | Role | Gates? | CaseHub tools |
|-------|------|---------------------|---------------|
| Investigator | Reads logs, correlates, identifies root cause; escalates if evidence insufficient | No (workerId="investigator" ≠ "resolver") | `casehub_checkpoint`, `casehub_done`, `casehub_escalate` |
| Resolver | Proposes and executes remediation | **Yes** (workerId="resolver" == "resolver") | `casehub_done` |

### Flow

```
1. scenario.sh → POST /example/incident-response/start
   ExampleController creates channels, registers Investigator, dispatches COMMAND:
     "You are the Investigator. P1 alert: payment-service error rate 34% since 02:47 UTC."

2. Investigator:
   [Second run: ChannelContextWindow injects prior channel history as appendSystemContext]
   GET /logs?service=payment-service&since=02:30 → fixture: 503s, DB pool exhausted
   GET /deploys?service=payment-service&since=02:00 → fixture: deploy 7f3a2c1 at 02:31
   casehub_checkpoint("<uuid-1>", "Finding: DB pool exhaustion correlates with deploy at 02:31")

   Happy path (deterministic with fixture logs — this is the demo path):
   casehub_done("investigator", "<uuid-1>",
     "Root cause: deploy 7f3a2c1 reduced DB pool size from 20 to 5. Recommended fix: restore to 20.")
   → DemoGateClassifier: workerId="investigator" ≠ "resolver" → Autonomous → DONE → FULFILLED

   Low-confidence path (system-prompt handles this; fixture logs always have enough data):
   casehub_escalate("investigator", "<uuid-1>",
     "Cannot determine root cause — log data is insufficient", "on-call-engineer")
   → HANDOFF dispatched to work channel
   → Qhorus transitions commitment to DELEGATED (terminal — isTerminal() == true)
   → ExampleController polling: checkState() returns DELEGATED → loop exits normally
   → ExampleController detects DELEGATED: logs "Investigator escalated to on-call-engineer;
     commitment transferred; operator takes over" → does NOT dispatch Resolver

3. ExampleController registers Resolver, dispatches:
   "You are the Resolver. Root cause confirmed: deploy 7f3a2c1 reduced DB pool 20→5. Execute fix."

4. Resolver:
   GET /services/payment-service/config (mock) → {"db.pool.size": 5}
   casehub_done("resolver", "<uuid-2>",
     "Ready to apply fix: PATCH /services/payment-service/config {db.pool.size: 20}")
   → DemoGateClassifier: workerId="resolver" == "resolver" → GateRequired → {"gated": true, ...}

5. OVERSIGHT GATE (wow moment):
   approve.sh → {"output": "approved"}
   → fulfill() → DONE → Resolver FULFILLED
   → ExampleController: PATCH /services/payment-service/config {"db.pool.size": 20}
   → {"updated": true}

   OR: approve.sh reject → {"output": "rejected"}
   → fulfill() dispatches DECLINE → commitment DECLINED
   → ExampleController: logs rejection summary (agent, proposed action, human decision), stops
   (No additional agent turn. The Qhorus ledger records the full decision chain.)
```

### CaseHub capabilities demonstrated

| Capability | Shown |
|---|---|
| Channel-backed commitment lifecycle | ✅ |
| `casehub_checkpoint` mid-investigation | ✅ |
| `casehub_escalate` (low-confidence path) | ✅ |
| ChannelContextWindow (second run) | ✅ |
| Single gate before production change | ✅ |
| Reject path (log and stop) | ✅ |
| Audit trail / incident timeline | ✅ |

---

## 8. What Each Example Does NOT Do

- Connect to real GitHub, real brokers, or real monitoring systems — all mocked
- Require an OpenClaw account — uses local gateway (but **requires** `ANTHROPIC_API_KEY`)
- Implement production-grade risk models or trading strategies — mock signal, mock thresholds
- Replace the CaseHub engine's case management — ExampleController is a demo orchestrator, not a production pattern
- Call `ChannelContextWindowService.closeCase()` — deliberately omitted so buffers persist across runs for the ChannelContextWindow demonstration

---

## 9. Capability Matrix

| Capability | Dev Team | Trading | Incident |
|---|:---:|:---:|:---:|
| Channel-backed commitment lifecycle | ✅ | ✅ | ✅ |
| Single oversight gate (DemoGateClassifier on agentId) | ✅ reviewer | ✅ execution | ✅ resolver |
| `gated: true` SKILL.md handling | ✅ | ✅ | ✅ |
| Multi-agent handoff (ExampleController) | ✅ | ✅ | ✅ |
| `casehub_create_workitem` (capability demo, non-causal) | ✅ | | |
| `casehub_checkpoint` (Watchdog reset) | ✅ | | ✅ |
| Reject path (DECLINE via fulfill) | | ✅ | ✅ |
| `casehub_escalate` (low-confidence path) | | | ✅ |
| ChannelContextWindow (second run) | | | ✅ |
| Audit trail in Qhorus | ✅ | ✅ | ✅ |

---

## 10. New production code in `app/`

Four classes added to `app/src/main/java/.../app/example/`:

| Class | Purpose | Guard |
|-------|---------|-------|
| `ExampleController` | JAX-RS resource; checks `casehub.example.enabled` at runtime, returns 503 when false. Endpoint is always registered by RESTEasy — it cannot be absent from the routing table using a runtime flag. | `casehub.example.enabled=true` checked in handler |
| `ExampleSetup` | `@ApplicationScoped @Transactional` setup delegate: channel creation, agent registration, COMMAND dispatch | none — always registered, harmless |
| `ExamplePoller` | `@ApplicationScoped @Transactional` polling delegate: JPA commitment state query; null = not yet visible, keep polling | none — always registered, harmless |
| `DemoGateClassifier` | `@RiskClassifier ActionRiskClassifier` — gates on `action.workerId()` matching `casehub.example.gate.agentid` | `casehub.example.gate.agentid` non-blank |

`DemoGateClassifier` is always registered as a CDI bean (no build-time condition). When `casehub.example.gate.agentid` is blank (the default), every `classify()` call returns `Autonomous` — completely inert. Runtime config only — no `@IfBuildProfile`, no `@UnlessBuildProperty`.

---

## 11. Out of Scope

- UI dashboard — terminal + curl for v1
- Real financial data or live market connectivity
- casehub-life integration — separate project
- Automated test suite for the examples — manual demo verification for v1
- `DEMO_MODE=scripted` (pre-recorded agent responses) — future option for deterministic conference demos
