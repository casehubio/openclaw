# Phase 2 Gate Wiring — Design Spec

**Issue:** casehubio/openclaw#30  
**Date:** 2026-06-09  
**Branch:** issue-30-phase2-gate-wiring

---

## Context

In openclaw#28 (tool-call-first completion architecture), the gate entry point was removed from `OversightGateService.evaluate()`. Completion signaling moved to MCP tool calls (`casehub_done`, etc.). This issue wires the gate entry into `CommitmentTools.done()`: when an agent calls `casehub_done`, the proposed action is classified; if `GateRequired`, an oversight gate is opened instead of closing the commitment immediately.

`engine#402` shipped `ActionRiskClassifier` SPI in `casehub-engine-api`. The blocking constraint is resolved.

---

## Scope

1. New `GateDecision` sealed interface (return type of `openGate()`)
2. New `OversightGateService.openGate()` method — classification + gate dispatch
3. Updated `OversightGateService.fulfill()` — closes agent's commitment on gate resolution
4. Updated `OversightGateDispatcher.dispatch()` — accepts `Optional<GateContext>` for full commitment closure
5. Updated `CommitmentTools.done()` — calls `openGate()`, returns pending gate response when gated
6. Unit tests for all new paths

Self-commits are out of scope — no oversight gate for self-tracked commitments.

---

## Architecture

### Classpath constraint

`casehub-openclaw-app` does not depend on `casehub-engine` runtime (by design — engine CDI beans have unsatisfied persistence SPIs). The engine's `ChainedReactiveActionRiskClassifier` is therefore not in the CDI container. `OversightGateService` replicates the composition logic (blocking) locally, using `@RiskClassifier Instance<ActionRiskClassifier>` from `casehub-engine-api` (already a dep of `casehub-openclaw-casehub`).

### Call flow — gated path

```
CommitmentTools.done(agentId, commitmentId, outcome)
  └─ OversightGateService.openGate(agentId, commitmentId, outcome)
       ├─ commitmentStore.findByCorrelationId(commitmentId) → work channelId
       ├─ channelService.findById(workChannelId) → caseId from channel name
       ├─ build PlannedAction(agentId, caseId, outcome, "COMPLETION", Map.of())
       ├─ classifyMostRestrictive():
       │     isUnsatisfied()        → Autonomous
       │     all Autonomous         → Autonomous
       │     any GateRequired       → mostRestrictive() + narrower()
       │     any classifier throws  → GateRequired("Classifier error — manual review required",
       │                                            reversible=true, null, null, null)
       ├─ Autonomous → return GateDecision.Autonomous
       └─ GateRequired:
             channelService.findByName("case-{caseId}/oversight") → oversightChannel
             oversightChannel missing → return GateDecision.Autonomous (fail-open)
             findCommandMessageId(commitmentId) → commandMessageId
             gateId = UUID.randomUUID()
             dispatch COMMAND to oversight channel:
               sender   = GATE_SENDER
               corrId   = gateId.toString()
               content  = serialized GateContent (Properties format)
             dispatch fails → return GateDecision.Autonomous (fail-open, log error)
             return GateDecision.GatePending(gateId, reason)

CommitmentTools.done() switch result:
  Autonomous  → existing channelBacked_done() unchanged
  GatePending → ToolResponse.success({"gated": true, "gateId": "<uuid>", "pendingReason": "<text>"})
```

### Call flow — gate resolution (fulfill)

```
OversightGateService.fulfill(gateId, rawOutput)
  ├─ find gate COMMAND message by correlationId=gateId (existing)
  ├─ parse GateContent from COMMAND message content → Optional<GateContent>
  ├─ approved = parseApproval(rawOutput) (existing first-token parse)
  ├─ find oversightChannel via commitment (existing)
  ├─ find workChannel via caseId (existing)
  └─ gateDispatcher.dispatch(approved, oversightChannelId, workChannelId,
                              commandMessageId, gateId, rawOutput,
                              Optional<GateContext>)

OversightGateDispatcher.dispatch():
  Approved + context present:
    RESPONSE  → oversight channel (corrId=gateId, inReplyTo=gateCommandMsgId)  — closes gate commitment
    DONE      → work channel (corrId=originalCommitmentId, inReplyTo=originalCommandMsgId) — closes agent commitment → FULFILLED
  Rejected + context present:
    DECLINE   → oversight channel (corrId=gateId, inReplyTo=gateCommandMsgId)  — closes gate commitment
    DECLINE   → work channel (corrId=originalCommitmentId, inReplyTo=originalCommandMsgId) — closes agent commitment → DECLINED
  Any path + context absent (restart / parse failure):
    as before: RESPONSE/DECLINE → oversight channel; STATUS → work channel (no commitment closure)
    log warn: "gate context missing — possible restart or content parse error; work commitment not closed"
```

---

## New Types

### `GateDecision` (public, `casehub/` module)

```java
// io.casehub.openclaw.casehub.GateDecision
public sealed interface GateDecision permits GateDecision.Autonomous, GateDecision.GatePending {
    record Autonomous() implements GateDecision {}
    record GatePending(UUID gateId, String reason) implements GateDecision {}
}
```

### `GateContent` (package-private, inside `OversightGateService`)

```java
private record GateContent(
    String originalCommitmentId,
    UUID workChannelId,
    long commandMessageId,
    String reason) {}
```

Serialized as Java Properties (std-lib only, no Jackson dep needed in library module):

```
originalCommitmentId=<uuid>
workChannelId=<uuid>
commandMessageId=<long>
reason=<text — may contain spaces; Properties.store() handles escaping>
```

Deserialized via `Properties.load(new StringReader(content))`. Parse failure → `Optional.empty()`.

---

## Classifier Composition

Blocking replication of `ChainedReactiveActionRiskClassifier` semantics (GE-20260607-3b6711, GE-20260607-326c7e):

```java
private RiskDecision classifyMostRestrictive(PlannedAction action) {
    if (classifiers.isUnsatisfied()) return new RiskDecision.Autonomous();
    RiskDecision result = new RiskDecision.Autonomous();
    for (ActionRiskClassifier c : classifiers) {
        try {
            result = mostRestrictive(result, c.classify(action));
        } catch (Exception e) {
            log.warnf("classifier %s threw — applying fail-safe GateRequired: %s",
                c.getClass().getSimpleName(), e.getMessage());
            return new RiskDecision.GateRequired(
                "Classifier error — manual review required before proceeding",
                true, null, null, null);
        }
    }
    return result;
}
```

`narrower()` picks the more restrictive of two `GateRequired` decisions:
- Fewer `candidateGroups` = more restrictive (null maps to `Integer.MAX_VALUE` — least restrictive)
- Equal size: shorter `expiresIn` wins

---

## PlannedAction Population

| Field | Value |
|-------|-------|
| `workerId` | `agentId` (the OpenClaw agent string) |
| `caseId` | extracted from work channel name via `CaseChannelNames.extractCaseId()` |
| `description` | `outcome` parameter (what the agent says it did) |
| `actionType` | `"COMPLETION"` (constant — OpenClaw completion event) |
| `context` | `Map.of()` |

---

## Fail-open Policy

| Condition | Behaviour |
|-----------|-----------|
| No `@RiskClassifier` beans (`isUnsatisfied()`) | `Autonomous` |
| All classifiers return `Autonomous` | `Autonomous` |
| Any classifier throws | `GateRequired` fail-safe (not Autonomous — classifier failure ≠ safe) |
| Work commitment missing or no channelId | `Autonomous` (shouldn't happen — caller guards) |
| Work channel not found | `Autonomous` |
| Oversight channel not found | `Autonomous` (oversight not configured = no gating) |
| Gate COMMAND dispatch throws | `Autonomous` (log error; can't open gate = proceed) |
| Gate context missing in `fulfill()` (restart / parse error) | Fall back to STATUS on work channel; log warn |

---

## `CommitmentTools` Changes

Constructor gains `OversightGateService oversightGateService`.

Only `channelBacked_done()` changes:

```java
private ToolResponse channelBacked_done(String agentId, String correlationId,
                                         UUID channelId, String outcome) {
    GateDecision gate = oversightGateService.openGate(agentId, correlationId, outcome);
    if (gate instanceof GateDecision.GatePending g) {
        return ToolResponse.success(
            """{"gated": true, "gateId": "%s", "pendingReason": "%s"}"""
                .formatted(g.gateId(), g.reason()));
    }
    // Autonomous — proceed with normal DONE dispatch
    long commandMessageId = findCommandMessageId(correlationId);
    if (commandMessageId < 0) {
        return ToolResponse.error("COMMAND_NOT_FOUND: ...");
    }
    DispatchResult result = messageService.dispatch(...DONE...);
    return ToolResponse.success(...);
}
```

`selfCommit_done()` is unchanged.

---

## `OversightGateDispatcher` Changes

New signature:

```java
@Transactional
void dispatch(boolean approved,
              UUID oversightChannelId,
              UUID workChannelId,
              long commandMessageId,        // gate COMMAND messageId (for inReplyTo on oversight)
              UUID gateId,
              String rawOutput,
              Optional<GateContext> gateContext)
```

All dispatches remain atomic within the single `@Transactional` boundary (the reason this class exists — two dispatches must commit together).

---

## Module Changes

| Module | Change |
|--------|--------|
| `casehub/` | New `GateDecision.java`; `OversightGateService` gains `openGate()`, `@RiskClassifier Instance<ActionRiskClassifier>` injection, updated `fulfill()`; `OversightGateDispatcher` updated |
| `app/` | `CommitmentTools` gains `OversightGateService` injection, updated `channelBacked_done()` |
| No new Maven deps | `casehub-engine-api` already in `casehub/` pom |

---

## Test Plan

All unit tests (Mockito, no Quarkus CDI). Tests live in the same module as the class under test.

### `OversightGateServiceTest` (new tests)

| Test | Asserts |
|------|---------|
| `openGate_noClassifiers_returnsAutonomous` | `isUnsatisfied()` path → `Autonomous` |
| `openGate_classifierReturnsAutonomous_returnsAutonomous` | Single classifier returning `Autonomous` |
| `openGate_classifierReturnsGateRequired_dispatchesCommandToOversightAndReturnsPending` | COMMAND dispatched to oversight channel; returns `GatePending` with expected gateId and reason |
| `openGate_commandContentRoundtrip` | Serialized content is parseable back to `GateContent` with all fields intact |
| `openGate_classifierThrows_returnsGateRequiredFailSafe` | Exception → `GateRequired` fail-safe decision → gate dispatched |
| `openGate_oversightChannelMissing_returnsAutonomous` | Channel not found → Autonomous |
| `openGate_dispatchThrows_returnsAutonomous` | `messageService.dispatch()` throws → Autonomous (fail-open) |
| `fulfill_approved_withContext_dispatchesDoneToWorkChannel` | Approved + parsed context → RESPONSE to oversight + DONE to work with correct correlationId/inReplyTo |
| `fulfill_rejected_withContext_dispatchesDeclineToWorkChannel` | Rejected + parsed context → DECLINE to oversight + DECLINE to work |
| `fulfill_missingContext_fallsBackToStatus` | Malformed/absent context → STATUS to work channel (no DONE) |

### `OversightGateDispatcherTest` (new tests)

| Test | Asserts |
|------|---------|
| `dispatch_approved_withContext_dispatchesDoneAndResponse` | Three total dispatches; RESPONSE to oversight; DONE to work with originalCommitmentId |
| `dispatch_rejected_withContext_dispatchesDeclineAndDecline` | DECLINE to oversight; DECLINE to work |
| `dispatch_approved_noContext_dispatchesResponseAndStatus` | Falls back to STATUS on work channel |
| `dispatch_rejected_noContext_dispatchesDeclineAndStatus` | Falls back to STATUS on work channel |

### `CommitmentToolsTest` (new tests)

| Test | Asserts |
|------|---------|
| `done_channelBacked_autonomous_proceedsWithDone` | `openGate()` → Autonomous → DONE dispatched, `{"closed": true}` returned |
| `done_channelBacked_gatePending_returnsPendingGate` | `openGate()` → GatePending → `{"gated": true, "gateId": ...}` returned; no DONE dispatched |
| `done_selfCommit_gateServiceNotCalled` | selfCommit_done() path never calls `openGate()` |

### Existing tests

`OversightGateServiceTest.fulfill_*` — the mock `gateDispatcher.dispatch()` stub must be updated to match the new 7-arg signature. The call-site assertions are updated to verify the new `Optional<GateContext>` argument.

`OversightGateDispatcherCdiTest` — add mock `OversightGateService` since `CommitmentTools` now injects it.

---

## Known Limitations (v1)

- Gate context survives restarts (persisted in Qhorus COMMAND message content)
- `pendingActionGate` in-memory-only issue (engine#433) does not apply here — this is a different pattern
- `actionType="COMPLETION"` is a fixed string; future work could use a richer context
- Gate COMMAND dispatch failure causes silent fail-open (agent proceeds unreviewed); log error provides observability
