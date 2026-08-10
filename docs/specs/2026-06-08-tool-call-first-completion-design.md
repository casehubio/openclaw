# Tool-Call-First Completion Architecture

**Date:** 2026-06-08
**Issue:** casehubio/openclaw#28
**Branch:** issue-28-tool-call-first-completion
**Layer:** L2 (`casehub/`) + `app/` + `skills/`

---

## Context and Root Cause

The speech act text protocol was introduced to classify agent output from `deliver:webhook`
into Qhorus message types (DONE, STATUS, DECLINE, FAILURE). In parallel, MCP tools
(`casehub_done`, `casehub_reject`, etc.) already dispatched the same typed messages
to Qhorus directly. This created an uncoordinated dual completion path:

**Path A — deliver:webhook → `OversightGateService.evaluate()` → `SpeechActClassifier`**
Agent produces `[DONE]` or `{"type":"DONE","content":"..."}` → text is parsed →
DONE dispatched to Qhorus → commitment FULFILLED.

**Path B — `casehub_done` MCP tool → `CommitmentTools.done()`**
Agent calls `casehub_done(agentId, commitmentId, outcome)` → DONE dispatched to
Qhorus → commitment FULFILLED.

These paths were not coordinated. When an agent used Path B and then produced natural
text output, `evaluate()` observed no open commitment, logged a spurious
"Watchdog may have expired" warning, and dispatched an extra STATUS message. The
OversightGate could not distinguish "commitment closed by tool call" from "Watchdog
expired it" — both produced `findOpenByObligor() → empty`.

Additionally, the speech act protocol was declared in `casehub-global/SKILL.md`
with `always: true`, making it visible to all agents (heartbeat, ad-hoc, queue)
that never operate on the `deliver:webhook` path. Protocol noise for agents that
can never use it.

**Root cause:** completion signaling was treated as a text parsing problem. Structured
signaling via MCP tools already existed and was already correct. The text protocol
was a parallel implementation of what the tools did better.

---

## Design Decision

**Tool calls are the sole completion signaling mechanism.**

- `casehub_done` → DONE to Qhorus → commitment FULFILLED
- `casehub_reject` → DECLINE to Qhorus → commitment DECLINED
- `casehub_escalate` / `casehub_delegate` → HANDOFF to Qhorus → commitment DELEGATED

**deliver:webhook delivers text as archival STATUS.** The agent's final text output
is posted as a non-resolving STATUS message on the work channel. It does not classify,
does not resolve commitments, and does not trigger oversight. It is a channel record —
human-readable output for audit and downstream case steps.

**commitmentId is injected into the COMMAND message — fully resolved.** `OpenClawChannelBackend.post()`
has both the real `agentId` (from `registry.findAgentId(caseId)`) and the COMMAND's
`correlationId` (= commitmentId, from `message.correlationId()`) in scope at invocation time.
Both are substituted into the injection block. The rendered text in the COMMAND is fully
concrete — no template variables the agent needs to resolve.

**Speech act classification layer is deleted.** `SpeechActClassifier`, `SpeechActDetection`,
`DefaultSpeechActClassifier`, `SpeechActContext`, `SpeechActResult`, `DetectionTier` —
all removed. The NLI enhancement path (openclaw#27) is closed as superseded.

**ActionRiskClassifier, PlannedAction, RiskDecision deleted.** These are local
placeholders for the engine#402 SPI that have no callers after evaluate() is simplified.
They will be imported from `casehub-engine-api` when the Phase 2 gate is wired through
`CommitmentTools.done()` (openclaw#30).

**OversightGateService survives, significantly simplified.** `evaluate()` becomes pure
archival. `fulfill()`, `OversightGateDispatcher`, and `/openclaw/delivery/oversight/{gateId}`
are retained intact — Phase 2 will re-wire the gate entry point from `evaluate()` to
`CommitmentTools.done()` without re-implementing the fulfillment path.

**OPEN → FULFILLED: ACKNOWLEDGED state is deliberately skipped.** In the previous design,
agents called `casehub_commit` before `casehub_done`. `casehub_commit` dispatches STATUS
with the correlationId — `MessageService.dispatch()` calls `commitmentService.acknowledge(correlationId)`,
transitioning OPEN → ACKNOWLEDGED and setting `Commitment.acknowledgedAt`. In the new
design, agents skip `casehub_commit` and go directly to `casehub_done`. The commitment
lifecycle is OPEN → FULFILLED with no ACKNOWLEDGED intermediate. `Commitment.acknowledgedAt`
is null for any case step where the agent did not call `casehub_commit` explicitly.

This is a deliberate choice. The COMMAND message with the injected context block
(containing the concrete `casehub_done` call) is the implicit acceptance signal — the
agent received the task and used the injected commitmentId to complete it. Any audit
query or operational dashboard that uses `acknowledgedAt != null` to identify explicit
agent acceptance will see no acknowledgment for case steps in this flow. That absence
is meaningful: it means the agent skipped explicit acknowledgment and went directly to
completion — not that the COMMAND was undelivered.

---

## Signal Flow (new)

```
CaseHub engine → COMMAND (correlationId=commitmentId) → Qhorus channel
                                │
                    OpenClawChannelBackend.post()
                    ├── filter: COMMAND only
                    ├── resolve agentId from OpenClawAgentRegistry
                    ├── inject fully-resolved commitment context into message
                    │     agentId + commitmentId both substituted at build time
                    └── hookClient.invoke(agentId, augmentedMessage, ...)
                                │
                           OpenClaw agent (during its turn)
                    ├── does work (may call casehub_checkpoint for long tasks)
                    ├── calls casehub_done("finance-agent", "abc-123", outcome)
                    │     → CommitmentTools.done() → DONE dispatched (inReplyTo=COMMAND)
                    │     → commitment FULFILLED
                    └── produces final text output (natural language)
                                │
            OpenClaw → POST /openclaw/delivery/channel/{channelId}
            (fires after turn completes — always after casehub_done tool call)
                                │
                    OversightGateService.evaluate()
                    └── dispatch(STATUS, text, no inReplyTo, no correlationId)
                          archival — non-resolving
```

**Message ordering in the channel:**
1. COMMAND (creates commitment, correlationId=commitmentId)
2. DONE — dispatched by `casehub_done` during the agent turn; `inReplyTo=COMMAND`, `correlationId=commitmentId` → fulfills commitment
3. STATUS — dispatched by `evaluate()` from the delivery webhook after turn completes; no inReplyTo, no correlationId → archival text record

The DONE and STATUS are always ordered correctly because `casehub_done` fires during the
agent's tool-call phase; the delivery webhook fires after the full turn output is collected.

`casehub_checkpoint`, `casehub_block`, `casehub_escalate`, `casehub_delegate` retain
their existing semantics — no changes to `CommitmentTools`.

---

## STATUS Audit Correlation — Architectural Constraint

`OversightGateService.evaluate()` receives `(UUID workChannelId, String agentId, String output)`
from `OpenClawDeliveryResource`. The webhook payload is `OpenClawDeliveryPayload`:

```java
public record OpenClawDeliveryPayload(
    @JsonAlias("agent_id") String agentId,
    @JsonAlias({"result", "content"}) String output
) {}
```

The delivery payload carries only `agentId` and `output`. There is no `correlationId`
field, and OpenClaw's delivery model provides no mechanism to include one. The STATUS
dispatched by `evaluate()` structurally cannot carry a `correlationId` — the information
is not present in the webhook body.

This is not a design gap. The authoritative completion record is the DONE message
dispatched by `casehub_done` during the agent turn, which IS correlated to the original
COMMAND via `inReplyTo` and `correlationId`. The STATUS is the archival text output,
recoverable by `agentId + channelId + timestamp`. The two records serve different purposes:
DONE signals and closes; STATUS preserves what the agent wrote.

**Qhorus dispatch semantics confirmed from bytecode.** `MessageService.dispatch()` guards
the commitment-transition switch with `if (dispatch.correlationId() != null)`. A STATUS
dispatch without correlationId bypasses the switch entirely — no `commitmentService.acknowledge()`
call, no state change, purely archival persistence and fanOut. By the time the delivery
webhook fires, the commitment is already FULFILLED by `casehub_done`. The archival STATUS
arrives at a closed commitment and causes no Qhorus state transition. This is the correct
and intended behavior.

---

## Phase 2 Gate Path (deferred — openclaw#30)

```
casehub_done(agentId, commitmentId, outcome)
    → CommitmentTools.done()
    → ActionRiskClassifier.classify(PlannedAction) [from casehub-engine-api engine#402]
    → if AUTONOMOUS  → dispatch DONE → commitment FULFILLED
    → if GATE_REQUIRED → OversightGateService.openGate()
                          → oversight agent invoked
                          → POST /openclaw/delivery/oversight/{gateId}
                          → OversightGateService.fulfill()
                          → OversightGateDispatcher.dispatch()
```

`OversightGateService.openGate()` is removed in this pass (dead code — no callers from
the archival evaluate()). It is re-added in openclaw#30. `fulfill()` and
`OversightGateDispatcher` are retained so the fulfillment path needs no re-implementation.

---

## Changes

### 1 — `OpenClawChannelBackend.post()` — fully-resolved commitment context injection

`OutboundMessage.correlationId()` is a `UUID` accessor on the Qhorus record. When
non-null, append a fully-resolved commitment context block to `message.content()`.
Both `agentId` and `commitmentId` are substituted at build time — the rendered text
in the COMMAND contains no template variables.

**Example rendered output (agentId="finance-agent", correlationId=abc-123-def-456):**

```
[original COMMAND content]

---
CaseHub commitment active.
  commitmentId: abc-123-def-456

  Complete   → casehub_done("finance-agent", "abc-123-def-456", outcome)
  Decline    → casehub_reject("finance-agent", "abc-123-def-456", reason)
  Progress   → casehub_checkpoint("finance-agent", "abc-123-def-456", note)
  Escalate   → casehub_escalate("finance-agent", "abc-123-def-456", reason, toAgent?)
  Delegate   → casehub_delegate("finance-agent", "abc-123-def-456", reason, toAgent)
  Block      → casehub_block("finance-agent", "abc-123-def-456", reason, blockedUntil)
```

All six commitment operations requiring `commitmentId` are listed. An agent that decides
to escalate or delegate mid-task has the concrete call in context; it does not need to
infer that the commitmentId applies.

When `correlationId()` is null (COMMAND without a commitment — edge case), use
`message.content()` as-is with no injection.

The injection is appended, not prepended — task instruction first, commitment context
after. No new dependencies on `OpenClawChannelBackend`. `CommitmentStore` is not needed:
`correlationId` is carried on the message itself.

### 2 — `OversightGateService.evaluate()` — archival STATUS dispatch

```java
public void evaluate(UUID workChannelId, String agentId, String output) {
    try {
        if (output == null || output.isBlank()) return;
        messageService.dispatch(MessageDispatch.builder()
                .channelId(workChannelId)
                .sender(agentId)
                .type(MessageType.STATUS)
                .content(output)
                .actorType(ActorType.AGENT)
                .build());
    } catch (Exception e) {
        log.errorf("evaluate() failed to archive webhook output for channel=%s agent=%s: %s",
                workChannelId, agentId, e.getMessage());
    }
}
```

STATUS dispatch has no `inReplyTo` or `correlationId` — structurally unavailable from
the webhook payload (see §STATUS Audit Correlation). Compliant with Qhorus protocol:
STATUS does not require either field. The dispatch enforcement gate (PP-20260523-a08b97)
is satisfied — all channel writes go through `MessageService.dispatch()`.

Five constructor dependencies removed: `SpeechActClassifier`, `ActionRiskClassifier`,
`OpenClawHookClient`, `OpenClawClientConfig`, `OpenClawCasehubConfig`.

Removed private methods:
- `openGate()`, `buildOversightPrompt()` — re-added in openclaw#30
- `resolveCommandMessageId()` — private helper with exactly one caller in the old `evaluate()`;
  `fulfill()` inlines its own COMMAND lookup (different return sentinel: -1L vs null) and
  never called this helper. Zero callers post-rewrite — deleted.

Retained: `fulfill()`, `parseApproval()`, `ChannelService`, `CommitmentStore`,
`MessageService`, `OversightGateDispatcher`.

### 3 — Classes deleted

All from `casehub/src/main/java/io/casehub/openclaw/casehub/`:

| File | Reason |
|------|--------|
| `SpeechActClassifier.java` | SPI deleted — tool calls own completion signaling |
| `DefaultSpeechActClassifier.java` | Implementation of deleted SPI |
| `SpeechActContext.java` | Input record for deleted SPI |
| `SpeechActResult.java` | Output record for deleted SPI |
| `SpeechActDetection.java` | Detection utility with no remaining callers |
| `DetectionTier.java` | Enum for deleted detection pipeline |
| `ActionRiskClassifier.java` | Local placeholder — no callers, replaced by engine#402 in openclaw#30 |
| `DefaultActionRiskClassifier.java` | Implementation of deleted local placeholder |
| `PlannedAction.java` | Input record for deleted local placeholder |
| `RiskDecision.java` | Output type for deleted local placeholder |

### 4 — Test files deleted

| File | Deleted types referenced |
|------|--------------------------|
| `SpeechActDetectionTest.java` | `SpeechActDetection`, `SpeechActResult`, `DetectionTier` |
| `DefaultSpeechActClassifierTest.java` | `DefaultSpeechActClassifier`, `SpeechActContext`, `SpeechActResult`, `DetectionTier` |
| `DefaultActionRiskClassifierTest.java` | `DefaultActionRiskClassifier`, `PlannedAction`, `RiskDecision` |

### 5 — `OversightGateServiceTest.java` — rewritten evaluate() tests

Old tests: speech act classification branches, risk decision branches, gate-open path.
All removed (all reference `SpeechActClassifier`, `ActionRiskClassifier`, or `RiskDecision`).

**Setup changes:** The test class constructs `OversightGateService` with mocked dependencies.
After Change #2 removes 5 dependencies from the constructor, the mock setup changes:
- Remove `@Mock SpeechActClassifier speechActClassifier` field and all `when(speechActClassifier...)` stubs
- Remove `@Mock ActionRiskClassifier actionRiskClassifier` field and all `when(actionRiskClassifier...)` stubs
- Remove `@Mock OpenClawHookClient hookClient`, `@Mock OpenClawClientConfig clientConfig`,
  `@Mock OpenClawCasehubConfig casehubConfig` fields (used only by the deleted gate path)
- Update `OversightGateService` constructor call to pass only the 4 retained dependencies:
  `ChannelService`, `CommitmentStore`, `MessageService`, `OversightGateDispatcher`

New tests for `evaluate()`:

| Test | Assertion |
|------|-----------|
| `evaluate_withOutput_archivesAsStatus` | `messageService.dispatch()` called once with `MessageType.STATUS`, content = output, sender = agentId, no inReplyTo, no correlationId |
| `evaluate_withNullOutput_noDispatch` | `messageService.dispatch()` never called |
| `evaluate_withBlankOutput_noDispatch` | `messageService.dispatch()` never called |
| `evaluate_dispatchException_failsOpen` | Exception from `dispatch()` does not propagate |

All existing `fulfill_*` tests retained unchanged — they reference only `ChannelService`,
`CommitmentStore`, `MessageService`, and `OversightGateDispatcher`, none of which are removed.

### 6 — `OpenClawChannelBackendTest.java` — injection tests added

| Test | Assertion |
|------|-----------|
| `post_command_withCorrelationId_injectsFullyResolvedContext` | `hookClient.invoke()` receives message containing the literal agentId string, the literal commitmentId UUID string, and all six tool call lines |
| `post_command_nullCorrelationId_usesContentAsIs` | `hookClient.invoke()` receives `message.content()` unchanged (no appended block) |

Existing `post_command_invokesAgent` test: the `OutboundMessage` helper passes `null`
correlationId → no injection → message equality assertion continues to hold for
`"Analyse this PR"` exactly. A second test with a non-null correlationId covers the
injection path separately.

### 7 — `BidirectionalWiringTest.java` — gate path removed; injection path added

The `@QuarkusTest` bidirectional integration test requires four coordinated changes:

**Remove (compilation failures after §3 deletions):**
- Import `io.casehub.openclaw.casehub.ActionRiskClassifier`
- Import `io.casehub.openclaw.casehub.RiskDecision`
- Field `@InjectMock ActionRiskClassifier actionRiskClassifier`
- Line in `@BeforeEach`: `when(actionRiskClassifier.classify(any())).thenReturn(new RiskDecision.Autonomous())`

**Delete tests (dead code for deferred gate path — openclaw#30):**
- Test 3: `gate_required_posts_command_to_oversight_channel`
- Test 4: `gate_required_invokes_openclaw_with_oversight_url`
- Test 5: `oversight_approval_dispatches_response_and_status`
- Test 6: `oversight_rejection_dispatches_decline`

All four reference `RiskDecision.GateRequired` and exercise `openGate()` which is deleted.

**Retain unchanged (no dependency on deleted types):**
- Test 1: `command_invokes_openclaw_with_correct_body` — `dispatchCommand()` passes no correlationId → `OutboundMessage.correlationId()` is null → no injection → `request.message()` = `"Analyse the budget."` exactly. Assertion holds.
- Test 2: `delivery_webhook_dispatches_status_to_work_channel` — already tests the new archival STATUS behavior
- Test 7: `deliver_unknown_channel_returns_404`
- Test 8: `deliver_invalid_uuid_returns_400`
- Test 9: `oversight_delivery_unknown_gateId_returns_200` — tests `fulfill()` fail-open; retained path

**Update class javadoc:** remove `{@link ActionRiskClassifier}` reference and gate path flow items; describe two retained scenarios: (1) COMMAND → OpenClaw invocation; (2) delivery webhook → STATUS archival.

**Add new integration test for the injection path:**

```java
@Test
void command_with_correlationId_injects_commitment_context() {
    String commitmentId = UUID.randomUUID().toString();

    messageService.dispatch(MessageDispatch.builder()
            .channelId(workChannelId)
            .sender("orchestrator")
            .type(MessageType.COMMAND)
            .content("Analyse the budget.")
            .correlationId(commitmentId)
            .actorType(ActorType.HUMAN)
            .build());

    ArgumentCaptor<AgentInvocationRequest> captor = ArgumentCaptor.forClass(AgentInvocationRequest.class);
    verify(gatewayClient).invokeAgent(captor.capture());

    AgentInvocationRequest request = captor.getValue();
    assertThat(request.message()).startsWith("Analyse the budget.");
    assertThat(request.message()).contains(commitmentId);
    assertThat(request.message()).contains("test-agent");
    assertThat(request.message()).contains("casehub_done");
}
```

This test is the only end-to-end coverage of the injection path at the `@QuarkusTest` level.
The `dispatchCommand()` helper is not used here (it dispatches without correlationId); the test
inlines the dispatch to set `.correlationId(commitmentId)`.

### 8 — `casehub-global/SKILL.md` — speech act section removed; call guidance updated; version bumped

**Version:** `1.0.0` → `2.0.0` — breaking behavioral change (speech act protocol
removed; case step completion model changed).

**Remove:** the entire "Case step responses" section (lines 47–71 in current file).

**Update "When to call these explicitly":** `casehub_commit` is no longer the required
first call when receiving a COMMAND as a case step. The commitmentId is injected into
the COMMAND message directly. The updated guidance:

> **For case steps:** your `commitmentId` is provided in the COMMAND message.
> Call `casehub_done` directly when the task is complete. Call `casehub_commit` only
> if you need to send an early STATUS acknowledgment to reset the Watchdog before
> completing — for example, when the task will take longer than the default Watchdog TTL.
>
> Call `casehub_reject` if you cannot proceed. Call `casehub_checkpoint` for long-running
> tasks to prevent false escalation. Call `casehub_escalate` when a task exceeds your
> authority or capability. Call `casehub_block` when blocked on an external dependency
> — extend the deadline rather than letting the Watchdog fire prematurely. Call
> `casehub_delegate` when intentionally transferring responsibility to a specific named party.

**Retain:** all tool descriptions (casehub_commit, casehub_done, casehub_reject, etc.)
and the "Open commitments" paragraph. The `always: true` flag is unchanged — tool
descriptions are universally relevant for any agent in a CaseHub environment.

---

## ARC42STORIES Update Required

These sections are stale after this change and must be updated in the same branch:

| Section | Current (stale) | Update needed |
|---------|-----------------|---------------|
| §4 Strategy — pattern table | `SpeechActClassifier` listed as Strategy SPI | Remove `SpeechActClassifier` entry; note completion signaling is via MCP tools (tool-call-first) |
| §5 Building Block View — OversightGateService | "Classifies agent output; opens human oversight gate on GATE_REQUIRED" | "Archives webhook text output as STATUS; `fulfill()` handles oversight gate responses (gate re-wired at `CommitmentTools.done()` in openclaw#30)" |
| §6 Runtime View — Scenario 1 | Describes speech act classification flow (SpeechActClassifier → dispatch DONE/STATUS) | Rewrite to describe tool-call-first: agent calls casehub_done → DONE dispatched → commitment FULFILLED; delivery webhook → STATUS archived |

---

## Platform Coherence

**Dispatch enforcement gate (PP-20260523-a08b97):** All channel writes in both the
archival path (STATUS in `evaluate()`) and the tool call path (`CommitmentTools.done()`)
go through `MessageService.dispatch()`. Protocol satisfied.

**No workarounds (PP-20260522-3b1ccd):** This change fixes the design rather than
adding a shim. No backward-compatibility wrapper for the speech act protocol.

**SPI impls same commit (PP-20260530-88cdf9):** Both the SPI (`SpeechActClassifier`)
and its implementation (`DefaultSpeechActClassifier`) are deleted in the same commit.
No stale implementation can remain.

**Known Placement Violation — OversightGateService:** PLATFORM.md flags this as
intended for `casehub-engine-api`. This pass simplifies `OversightGateService`
significantly (5 fewer dependencies, evaluate() is ~10 lines). The simplification
makes extraction cheaper. openclaw#31 tracks the extraction.

**Cross-dep map cleanup:** The PLATFORM.md row
`casehub-inference-api → casehub-openclaw casehub — TextClassifier — SpeechActClassifier NLI implementation`
must be removed (parent#199 filed).

---

## Issues Filed (deferred concerns)

| Issue | What |
|-------|------|
| openclaw#30 | Phase 2 gate wiring: `CommitmentTools.done()` → `ActionRiskClassifier` → `OversightGateService.openGate()` |
| openclaw#31 | Extract `OversightGateService` to `casehub-engine-api` (PLATFORM.md known placement violation) |
| openclaw#27 | Closed — superseded by this design (`NliSpeechActClassifier` not needed, SpeechActClassifier SPI deleted) |
| parent#199 | Remove `casehub-inference-api → casehub-openclaw` cross-dep map row for `SpeechActClassifier` NLI |

---

## Files Changed

| File | Change |
|------|--------|
| `casehub/.../OpenClawChannelBackend.java` | Inject fully-resolved commitment context block (agentId + commitmentId substituted at build time; all 6 tool calls) |
| `casehub/.../OversightGateService.java` | `evaluate()` → archival STATUS; remove 5 deps; delete `openGate()`, `buildOversightPrompt()` |
| `casehub/.../SpeechActClassifier.java` | **DELETE** |
| `casehub/.../DefaultSpeechActClassifier.java` | **DELETE** |
| `casehub/.../SpeechActContext.java` | **DELETE** |
| `casehub/.../SpeechActResult.java` | **DELETE** |
| `casehub/.../SpeechActDetection.java` | **DELETE** |
| `casehub/.../DetectionTier.java` | **DELETE** |
| `casehub/.../ActionRiskClassifier.java` | **DELETE** |
| `casehub/.../DefaultActionRiskClassifier.java` | **DELETE** |
| `casehub/.../PlannedAction.java` | **DELETE** |
| `casehub/.../RiskDecision.java` | **DELETE** |
| `casehub/test/.../SpeechActDetectionTest.java` | **DELETE** |
| `casehub/test/.../DefaultSpeechActClassifierTest.java` | **DELETE** |
| `casehub/test/.../DefaultActionRiskClassifierTest.java` | **DELETE** |
| `casehub/test/.../OversightGateServiceTest.java` | Rewrite evaluate() tests + setup (remove 5 mock fields); retain fulfill() tests unchanged |
| `casehub/test/.../OpenClawChannelBackendTest.java` | Add fully-resolved injection tests; null-correlationId no-injection test |
| `app/test/.../BidirectionalWiringTest.java` | Remove ActionRiskClassifier mock + 2 imports + @BeforeEach stub; delete tests 3–6 (gate path, deferred to #30); retain tests 1, 2, 7–9; add injection path integration test; update class javadoc |
| `app/.../mcp/CommitmentTools.java` | Update `@Tool` description for `commit()` — remove implied sequencing ("pass to casehub_done when complete"); replace with: for case steps, commitmentId is in the COMMAND — call casehub_done directly; use casehub_commit only for explicit Watchdog acknowledgment on long-running tasks |
| `app/.../OpenClawDeliveryResource.java` | Replace stale class javadoc: remove speech act classification claim, remove openclaw#16 and openclaw#10 references (superseded by #28); new text: delegates to `OversightGateService.evaluate()` which archives output as STATUS; always 200; OpenClaw must not retry |
| `skills/casehub-global/SKILL.md` | Version 2.0.0; remove speech act section; update casehub_commit call guidance |
| `ARC42STORIES.MD` | Update §4, §5, §6 per table above |

No Flyway migrations. No Python or TypeScript changes. No new Maven dependencies.
Net: 10 production classes deleted, 3 test files deleted (`SpeechActDetectionTest`,
`DefaultSpeechActClassifierTest`, `DefaultActionRiskClassifierTest`), 3 private methods
deleted (`openGate`, `buildOversightPrompt`, `resolveCommandMessageId`), 2 production
classes significantly simplified (`OversightGateService`, `OpenClawChannelBackend`),
1 test class rewritten with gate tests deleted and injection test added (`BidirectionalWiringTest`),
1 test class rewritten with setup trimmed (`OversightGateServiceTest`),
2 non-test source files updated (`CommitmentTools` @Tool annotation, `OpenClawDeliveryResource` javadoc),
1 skill file updated, ARC42STORIES updated.

---

## Out of Scope

- Phase 2 gate wiring through `CommitmentTools.done()` → openclaw#30
- `OversightGateService` extraction to `casehub-engine-api` → openclaw#31
- `casehub_block` mutation of `Commitment.expiresAt` direct field access → qhorus#250
- Multi-tenancy propagation through provisioner and channel bridge → openclaw#29
