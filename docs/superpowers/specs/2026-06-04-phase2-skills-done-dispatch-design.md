# Design: Phase 2 Layer 3 Skills, casehub_block MCP Tool, and DONE Dispatch Fix

**Date:** 2026-06-04  
**Branch:** issue-23-layer3-lifecycle-skills  
**Issues:** casehubio/openclaw#23 (Phase 2 skills), #24 (ActionRiskClassifier), #16 (DONE dispatch)  
**Epic:** casehubio/openclaw#8 (Speech act classification)

---

## 1. Scope

Three independent work items sharing a branch:

| Item | Issue | Scale |
|------|-------|-------|
| Layer 3 lifecycle SKILL.md files + `casehub_block` MCP tool + `casehub_delegate` MCP tool | #23 | M |
| ActionRiskClassifier javadoc confirmation | #24 | XS |
| Proper DONE dispatch on autonomous path | #16 | M |

Commit structure: each issue gets at least one commit with a `Refs #N` / `Closes #N` reference. No single "big bang" commit.

---

## 2. casehub_block MCP Tool

### Qhorus Watchdog model (important context)

The Watchdog fires on `Commitment.expiresAt` only. `expireOverdue()` queries `findExpiredBefore(Instant.now())` which is `WHERE expiresAt < cutoff AND state NOT IN (terminal states)`. STATUS dispatch sets `acknowledgedAt` (OPEN → ACKNOWLEDGED) but does NOT modify `expiresAt`. There is no TTL-from-last-STATUS. Updating `expiresAt` is the only mechanism to prevent premature Watchdog expiry.

Note: casehub-global SKILL.md's description of `casehub_checkpoint` as "resets the Watchdog TTL" is inaccurate — it only transitions state to ACKNOWLEDGED, not extend the deadline. This is a pre-existing documentation gap; tracked separately.

### Problem

No existing tool covers "I cannot proceed until an external dependency resolves — extend the Watchdog deadline to prevent premature expiry." `casehub_checkpoint` dispatches STATUS and moves state to ACKNOWLEDGED but does NOT change `expiresAt`. `casehub_escalate` (HANDOFF) transfers the obligation — wrong semantics for a temporary block where the original agent intends to resume.

### Design

Add `casehub_block` to `CommitmentTools.java`.

**Signature:**
```java
casehub_block(agentId, commitmentId, reason, blockedUntil)
// blockedUntil: ISO-8601 Instant — new expiresAt for the Watchdog
```

**Behaviour:**
1. Look up `UUID channelId = channelMap.get(commitmentId)` — O(1) routing, consistent with all other tools
2. Fetch the entity: `commitmentStore.findByCorrelationId(commitmentId)` 
3. Guard: if empty → `COMMITMENT_NOT_FOUND`; if `c.state.isTerminal()` → `COMMITMENT_ALREADY_CLOSED`; if `!c.obligor.equals(agentId)` → `COMMITMENT_UNAUTHORIZED` (prevents silent cross-agent deadline extension)
4. Update `commitment.expiresAt = Instant.parse(blockedUntil)` and call `commitmentStore.save(commitment)` — extends the Watchdog deadline
5. If channel-backed (`channelId != null`): dispatch STATUS with content `"BLOCKED: <reason>"` — the `"BLOCKED: "` prefix distinguishes blocked-STATUS from progress-STATUS for channel observers, though the MessageType is the same
6. Return `{"blocked": true, "newWatchdogDeadline": "<ISO>"}`

**`@Transactional` on `block()`:** Unlike other tool methods (which delegate to already-`@Transactional` service methods), `block()` calls `commitmentStore.save()` directly. Annotating `block()` with `@Transactional` is correct here because: (a) `block()` contains no try/catch — it propagates exceptions normally, so there is no rollback-only problem (contrast with `OversightGateDispatcher`, which was introduced specifically for `OversightGateService.fulfill()`'s catch-and-swallow pattern); (b) `commitmentStore.save()` and `messageService.dispatch()` both participate in the same JPA/JTA resource and will join the transaction opened by `block()`'s `@Transactional` annotation.

**State machine note:** `CommitmentService` has no `extendDeadline()` operation. Updating `expiresAt` directly via `CommitmentStore.save()` bypasses the service layer. This is safe — the state machine manages state transitions (OPEN/ACKNOWLEDGED/etc.), not the deadline field. No state invariant is violated by extending `expiresAt` on a non-terminal commitment. The right qhorus fix is `CommitmentService.extendDeadline(correlationId, newDeadline)` — a handful of lines; tracked as the qhorus deferred issue. Direct mutation is the in-session workaround.

**Unblocking:** when the block resolves, the agent calls `casehub_checkpoint("UNBLOCKED: <note>")`. The SKILL.md guides agents to do this.

**`COMMITMENT_UNAUTHORIZED` rationale:** none of the existing tools (done, reject, checkpoint, escalate) check `commitment.obligor`. `block()` is the exception because deadline extension is silently consequential — any agent with a known `commitmentId` can extend another agent's Watchdog indefinitely, with no visible audit signal. That misuse potential is unique to this operation. The guard is intentional asymmetry, not an oversight.

**Error cases:**
- `COMMITMENT_NOT_FOUND`: no commitment found for `commitmentId`
- `COMMITMENT_ALREADY_CLOSED`: commitment is in a terminal state
- `COMMITMENT_UNAUTHORIZED`: `commitment.obligor != agentId`
- `INVALID_DEADLINE`: `blockedUntil` cannot be parsed as ISO-8601
- `DEADLINE_IN_PAST`: `blockedUntil` is before `Instant.now()` — would cause the Watchdog to fire immediately

### Tests

- `block()` with channel-backed commitment: `expiresAt` updated; STATUS dispatched with `"BLOCKED: reason"`; `{"blocked": true, "newWatchdogDeadline": "..."}` returned
- `block()` with self-commit (no channelMap entry): `expiresAt` updated; no message dispatched
- `block()` with unknown commitmentId: `COMMITMENT_NOT_FOUND` error
- `block()` with terminal commitment: `COMMITMENT_ALREADY_CLOSED` error
- `block()` with wrong agentId: `COMMITMENT_UNAUTHORIZED` error
- `block()` with invalid `blockedUntil`: `INVALID_DEADLINE` error
- After `block()`: `findExpiredBefore(now)` does NOT return the commitment (Watchdog skip confirmed)
- `block()` with `blockedUntil` in the past: `DEADLINE_IN_PAST` error
- `@Transactional` partial-failure direction: if `messageService.dispatch()` throws AFTER `commitmentStore.save()` succeeds, the JTA transaction rolls back and `findByCorrelationId()` returns the ORIGINAL `expiresAt` (not the extended one). This is the critical direction — confirms atomicity of save + dispatch.

---

## 3. casehub_delegate MCP Tool

### Why a dedicated tool (not reuse casehub_escalate)

`casehub_escalate` dispatches HANDOFF with optional `toAgent`. "Delegation" (intentional transfer to a named party) and "escalation" (authority/capability exceeded, target may be unspecified) both produce HANDOFF in the Qhorus ledger — structurally identical. The distinction is lost in the audit trail permanently if the same tool handles both.

`casehub_delegate` makes the distinction machine-readable: `toAgent` is required (not optional), and the tool description captures the delegation intent. This is ~10 lines and cannot be retrofitted later without a migration.

### Design

Add `casehub_delegate` to `CommitmentTools.java`.

**Signature:**
```java
casehub_delegate(agentId, commitmentId, reason, toAgent)
// toAgent: required (unlike casehub_escalate where it is optional)
```

**Behaviour:** identical to `casehub_escalate` except `toAgent` is `@ToolArg(required = true)`. Implementation:
1. `UUID channelId = channelMap.get(commitmentId)` — if null → `COMMITMENT_NOT_FOUND` (same as escalate: delegate requires a channel-backed commitment; self-commits cannot be delegated via this tool)
2. `long commandMessageId = findCommandMessageId(commitmentId)` — if -1 → `COMMAND_NOT_FOUND` (HANDOFF requires `inReplyTo`)
3. Dispatch HANDOFF with `content = reason`, `target = toAgent`, `inReplyTo = commandMessageId`, `correlationId = commitmentId` — `reason` is the ledger record of why delegation occurred
4. `channelMap.remove(commitmentId)` — obligation transferred, this agent's turn is done
5. Return `{"delegated": true, "delegatedTo": "<toAgent>"}`

**Distinction in tool description:** "Intentional transfer of a commitment to a named agent or person. Use when you are delegating responsibility, not when escalating for authority or capability reasons." Contrast with `casehub_escalate`: "Use when a task exceeds your authority or capability."

**Self-commit note:** `casehub_delegate` and `casehub_escalate` both require a channel-backed commitment (channelMap entry). Self-commits (created without `channelId`) cannot be delegated — there is no channel to dispatch HANDOFF to. If the agent has a self-commit it wants to hand off, it should close with `casehub_done` and re-open via `casehub_create_workitem` with a target assignee.

### Tests

- `delegate()` with valid channel-backed commitment and `toAgent`: HANDOFF dispatched with `target = toAgent`, correct `inReplyTo`, and `content = reason`; `{"delegated": true, "delegatedTo": "..."}` returned
- `delegate()` with missing `toAgent`: framework-level error (required arg)
- `delegate()` with unknown commitmentId (no channelMap entry): `COMMITMENT_NOT_FOUND`
- `delegate()` when COMMAND message not found: `COMMAND_NOT_FOUND`

---

## 4. Layer 3 SKILL.md Files

Three new SKILL.md files in `skills/`. All follow the existing pattern. All are stateless.

### 4.1 casehub-reject (`skills/casehub-reject/SKILL.md`)

**Triggers:** "reject this task", "I can't complete this", "decline this commitment", "this isn't possible", "I won't be able to do this"

**Tool:** `casehub_reject`

**Procedure:**
1. Confirm the agent has an active `commitmentId`. If not: "I don't have an active tracked commitment for this task. Was it created with `casehub_commit` or `casehub_create_workitem`?" — do not call reject without a valid ID
2. Extract `reason` (required)
3. Call `casehub_reject(agentId, commitmentId, reason)`
4. On `COMMITMENT_NOT_FOUND` or `COMMITMENT_ALREADY_CLOSED`: report to user — do not retry
5. On success: report `{"declined": true}` — obligation discharged, Watchdog disarmed

### 4.2 casehub-block (`skills/casehub-block/SKILL.md`)

**Triggers:** "I'm blocked on X", "waiting for X to resolve", "can't proceed until Y", "on hold pending Z", "blocked by X"

**Tools:** `casehub_block`, `casehub_checkpoint`

**Procedure:**
1. Confirm active `commitmentId`
2. Identify the `reason` (required — what is blocking)
3. Estimate `blockedUntil` (must be a future timestamp — a past value returns `DEADLINE_IN_PAST`):
   - If the resolution time is known or estimable → use it
   - If the blocker is indefinite (you have no idea when it resolves) → **consider `casehub_escalate` instead** (hand off to whoever can resolve it rather than blocking the Watchdog indefinitely)
   - If the blocker will resolve in a reasonable window but the exact time is unknown → extend by a reasonable estimate (1 hour, 4 hours, 1 day) and explain the estimate to the user
4. Call `casehub_block(agentId, commitmentId, reason, blockedUntil)`
5. Confirm `newWatchdogDeadline` to the user
6. Inform: "When the blocker resolves, call `casehub_checkpoint('UNBLOCKED: <note>')` to resume normal monitoring"

**Restart recovery warning:** "If the Quarkus service restarts while you are blocked, the in-memory channel binding for this commitment is lost. Calling `casehub_checkpoint` after a restart will return `COMMITMENT_NOT_FOUND` — do not retry it.

For channel-backed commitments: calling `casehub_done` after a restart will mark the commitment closed in the store, but no DONE will be dispatched to the work channel (the channel binding is gone). The human or orchestrator on that channel will not receive a completion signal; only the Watchdog escalation path will eventually surface it. If audit trail completeness matters, let the Watchdog handle escalation rather than calling `casehub_done` blindly.

The safest path after a restart: let the Watchdog escalate. A human reviewer can then inspect the commitment state and close it explicitly."

### 4.3 casehub-delegate (`skills/casehub-delegate/SKILL.md`)

**Triggers:** "delegate this to X", "hand this off to X", "give this to [agent/person]", "transfer this to X", "this should go to X"

**Tool:** `casehub_delegate`

**Procedure:**
1. Confirm active `commitmentId`
2. Identify `toAgent` (required — target agent ID or human identifier)
3. Clarify `reason` for delegation (recorded in the ledger)
4. Call `casehub_delegate(agentId, commitmentId, reason, toAgent)`
5. On `COMMITMENT_NOT_FOUND`: the commitment is not tracked in this session — it may already be closed or was committed in a previous session. Do not retry.
6. On `COMMAND_NOT_FOUND`: the original COMMAND message cannot be located (possible service restart). The Watchdog will handle escalation — do not retry `casehub_delegate`.
7. On success: report "Commitment transferred to [target]. Their Watchdog is now running. Your obligation is discharged."

---

## 5. casehub-global and README Updates

- **`casehub-global/SKILL.md`** — add `casehub_block` and `casehub_delegate` to the front-matter `tools:` list and the "Available tools" description section. Agents with only `casehub-global` loaded need to know these tools exist.
- **`skills/README.md`** — update skill count (5 → 8) and add entries for `casehub-reject`, `casehub-block`, `casehub-delegate`.

---

## 6. ActionRiskClassifier Confirmation (Issue #24)

**State:** engine#402 is OPEN. The local `ActionRiskClassifier` in `casehub/` already has an identical contract to the proposed engine-api SPI — same method signature, same type names, same `@Alternative @Priority(1)` override pattern (confirmed by reading both the local code and the engine#402 issue body on 2026-06-04).

**Work:** update the `ActionRiskClassifier` javadoc to explicitly state the contract has been verified identical to casehubio/engine#402 as of 2026-06-04, confirming the migration will be a pure import swap with no code changes beyond the import statement.

No changes to the interface body, implementations, or callers.

---

## 7. Proper DONE Dispatch — openclaw#16

### Problem

`OversightGateService.evaluate()` dispatches to the work channel on the autonomous path but lacks the COMMAND's Long `messageId` needed for `inReplyTo`. DONE, DECLINE, FAILURE, and RESPONSE all require `inReplyTo`. The current workaround substitutes STATUS, which acknowledges receipt but does not fulfill the Commitment.

### Root Cause

`OversightGateService.evaluate()` has `workChannelId` and `agentId` but not the correlationId of the original COMMAND. The correlationId is needed to look up the COMMAND message's Long `id` via `messageService.findAllByCorrelationId()`.

### Why not the in-memory registry approach

The obvious fix (store `agentId → correlationId` in `OpenClawAgentRegistry` at post() time) puts turn-scoped transient state into a session-scoped registry. The correlation is valid for exactly one turn — created in `post()`, consumed in `evaluate()`. This is architecturally wrong and introduces a race window (post() could overwrite correlationId before evaluate() consumes it if OpenClaw ever delivers concurrently for the same agent).

### Design — Qhorus-native query

`evaluate()` already has `agentId` and `workChannelId`. The original COMMAND commitment is persisted in Qhorus with `obligor = agentId` on `channelId = workChannelId`. Qhorus auto-creates the Commitment when a COMMAND is dispatched to the channel — `channelBacked_commit()` in `CommitmentTools` confirms this: it calls `commitmentStore.findOpenByObligor(agentId, channelId)` to find the auto-created Commitment rather than creating one itself. `obligor` is set by the channel dispatch path.

`Commitment.messageType` is a persisted JPA field (`@Column(name = "message_type", nullable = false)`) — confirmed present in the entity. The filter compiles. Under the 1:1 agentId↔case invariant there should be exactly one open COMMAND commitment per agent per channel, making the filter defensive rather than necessary; it prevents silent ambiguity if that invariant ever breaks.

**Implementation pattern — materialize list before streaming:**

```java
// Materialize first so hadCommitment can be set before streaming discards the list.
List<Commitment> open = commitmentStore.findOpenByObligor(agentId, workChannelId);
boolean hadCommitment = !open.isEmpty();
String correlationId = open.stream()
        .filter(c -> c.messageType == MessageType.COMMAND)
        .map(c -> c.correlationId)
        .findFirst()
        .orElse(null);

Long commandMessageId = resolveCommandMessageId(correlationId); // private helper

MessageDispatch.Builder builder = MessageDispatch.builder()
        .channelId(workChannelId)
        .sender(agentId)
        .content(output != null ? output : "")
        .actorType(ActorType.AGENT);

if (commandMessageId != null && correlationId != null) {
    builder.type(messageType).inReplyTo(commandMessageId).correlationId(correlationId);
} else {
    builder.type(MessageType.STATUS);
    if (hadCommitment) {
        // Commitment exists but COMMAND message is gone — data inconsistency.
        log.errorf("Open COMMAND commitment found for agentId=%s on channel=%s but " +
                   "COMMAND message lookup failed — Commitment will not be fulfilled; " +
                   "Watchdog will escalate. Operator attention required.",
                   agentId, workChannelId);
    } else {
        // No open Commitment — Watchdog likely expired it while the agent was in-flight.
        log.warnf("No open COMMAND commitment for agentId=%s on channel=%s — " +
                  "Watchdog may have expired it during agent execution. Dispatching STATUS.",
                  agentId, workChannelId);
    }
}
messageService.dispatch(builder.build());
```

**STATUS fallback — two distinct paths:**

1. **No open Commitment found** (`hadCommitment=false`): the Commitment was likely expired by the Watchdog while the agent was in-flight — not a routine outcome, not a data consistency error. The obligation is already terminal; STATUS is dispatched as a best-effort notification. Log at WARN.

2. **Commitment found but COMMAND message missing** (`hadCommitment=true`, `commandMessageId=null`): data inconsistency — the Commitment exists but its source COMMAND message is absent. The Commitment will not be fulfilled. Log at ERROR; requires operator attention.

**`MessageQueries` module boundary:** `CommitmentTools` is in `io.casehub.openclaw.app.mcp` (app module). `OversightGateService` is in `io.casehub.openclaw.casehub` (casehub module). A package-private `MessageQueries` in casehub would be inaccessible from app. **Resolution: accept the duplication.** `CommitmentTools` keeps its existing private `findCommandMessageId()` method. `OversightGateService` gets a private `resolveCommandMessageId()` helper (same two-line implementation). No shared class; two-line duplication is acceptable.

**No changes to OpenClawAgentRegistry or OpenClawChannelBackend.post()** — the Qhorus-native approach requires neither.

### Tests

- `evaluate()` with open COMMAND commitment in Qhorus: dispatches `messageType` (not STATUS) with correct `inReplyTo`
- `evaluate()` with no open COMMAND commitment (`hadCommitment=false`): dispatches STATUS; logs at WARN (not ERROR)
- `evaluate()` with open Commitment but COMMAND message not found (`hadCommitment=true`): dispatches STATUS; logs at ERROR
- `evaluate()` confirms `Commitment.messageType` filter is applied (defensive guard)

---

## 8. Deferred Issues

| Issue | Repo | Description | Scale |
|-------|------|-------------|-------|
| `CommitmentService.extendDeadline(correlationId, newDeadline)` | casehub-qhorus | Proper service-layer method for deadline extension; removes direct `expiresAt` mutation workaround in `casehub_block`. SUSPENDED state is the long-term answer; extendDeadline() is the near-term answer. | S |

File on casehubio/qhorus before leaving this session.

---

## 9. Files Changed (by issue)

### openclaw#23 — Phase 2 skills

| File | Change |
|------|--------|
| `casehub/src/main/java/.../CommitmentTools.java` | Add `casehub_block` and `casehub_delegate` tools |
| `skills/casehub-reject/SKILL.md` | New |
| `skills/casehub-block/SKILL.md` | New |
| `skills/casehub-delegate/SKILL.md` | New |
| `skills/casehub-global/SKILL.md` | Add `casehub_block`, `casehub_delegate` to tools list and description |
| `skills/README.md` | Update count (5→8), add three new skill entries |
| `casehub/src/test/java/.../CommitmentToolsTest.java` | casehub_block and casehub_delegate tests |

### openclaw#24 — ActionRiskClassifier

| File | Change |
|------|--------|
| `casehub/src/main/java/.../ActionRiskClassifier.java` | Javadoc update only — confirm contract match |

### openclaw#16 — DONE dispatch

| File | Change |
|------|--------|
| `casehub/src/main/java/.../OversightGateService.java` | Replace STATUS workaround; use Qhorus-native query; private `resolveCommandMessageId()` helper; two-path STATUS fallback logging |
| `casehub/src/test/java/.../OversightGateServiceTest.java` | DONE dispatch tests; two-path STATUS fallback (no-commit and data-gap) |

### ARC42STORIES.MD

| File | Change |
|------|--------|
| `ARC42STORIES.MD` | §8: document casehub_block and casehub_delegate as new MCP tool surface; note Qhorus-native DONE dispatch pattern |
