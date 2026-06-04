# C8 — Speech Act Classification Phase 2+3

**Date:** 2026-06-05
**Issues:** casehubio/openclaw#10, casehubio/openclaw#8
**Branch:** issue-10-c8-speech-act
**Layer:** L2 (`casehub/`)

---

## Context

`DefaultSpeechActClassifier` is a Phase 1 placeholder: it always returns `MessageType.DONE`
regardless of agent output content. `OversightGateService.evaluate()` then dispatches the raw
agent output to the Qhorus work channel typed as DONE.

Phase 2 adds bracket-prefix detection (`[STATUS] message`). Phase 3 adds structured JSON
detection (`{"type":"STATUS","content":"message"}`). Both are implemented together in this
pass. The fallback changes from DONE to STATUS — see §Fallback Design Decision.

---

## Classifiable MessageTypes

Verified against `MessageService.java` and `CommitmentService.java` (Qhorus) — each type
calls a distinct `CommitmentService` method and produces a distinct terminal/non-terminal state:

| Type | Commitment transition | Terminal? |
|------|-----------------------|-----------|
| `DONE` | FULFILLED | Yes |
| `STATUS` | ACKNOWLEDGED | **No** — Watchdog stays armed |
| `DECLINE` | DECLINED | Yes |
| `FAILURE` | FAILED | Yes |
| `RESPONSE` | FULFILLED | Yes |

**Multi-turn STATUS → DONE verified:** `ACKNOWLEDGED` is non-terminal (`isTerminal()` returns
false for ACKNOWLEDGED; only FULFILLED, DECLINED, FAILED, DELEGATED, EXPIRED are terminal).
`CommitmentService.fulfill()` uses `.filter(c -> !c.state.isTerminal())` — so dispatching DONE
after one or more STATUS messages correctly transitions ACKNOWLEDGED → FULFILLED. Multiple
STATUS messages are also safe: `acknowledge()` javadoc reads "Transitions OPEN or ACKNOWLEDGED
→ ACKNOWLEDGED." The multi-turn flow is fully supported at the Qhorus layer.

`HANDOFF` is excluded: it requires a `target` field unavailable on this path.
Use `casehub_delegate` MCP tool for intentional delegation.
`COMMAND`, `QUERY`, `EVENT` are never valid agent completion types.

**`RESPONSE` note:** `RESPONSE` is the correct reply to a `QUERY` obligation, not a `COMMAND`.
On the standard case-step path agents receive `COMMAND` — `DONE` is the correct completion
type. `RESPONSE` is classifiable on this path for the edge case where an agent is invoked in
response to a QUERY, but if in doubt use `DONE`.

---

## Architecture

Three-tier detection pipeline, ordered by explicitness:

```
Output text
  → SpeechActDetection.detect(output)      ← public utility class
        Tier 1: JSON parse   {"type":"DONE","content":"..."}  → Optional<SpeechActResult>
        Tier 2: Prefix parse [STATUS] some message            → Optional<SpeechActResult>
        Tier 3: Optional.empty()
  → DefaultSpeechActClassifier: fallback = SpeechActResult(STATUS, rawOutput, FALLBACK)
  → NliSpeechActClassifier (future, @Alternative @Priority(1)):
        fallback = TextClassifier.classify(output) → SpeechActResult
```

`SpeechActDetection` is public from the outset, with Javadoc marking it as an internal API
with no stability guarantee. The cross-module case (future NLI classifier in
`casehub-openclaw-inference`) requires public visibility — deferring promotion to that moment
creates a refactor forcing function at the worst time.

---

## SPI Changes (breaking — all callers listed)

### New: `DetectionTier` enum

```java
public enum DetectionTier { JSON, PREFIX, FALLBACK }
```

Added now to avoid a future SPI break. A future `NliSpeechActClassifier` adds `NEURAL`.

### New: `SpeechActResult` record

```java
public record SpeechActResult(MessageType type, String content, DetectionTier tier) {}
```

`content` is the stripped/cleaned message body for Qhorus dispatch — bracket prefix and JSON
envelope are removed. `tier` enables Tier 3 fallback logging and future observability.

### Breaking: `SpeechActClassifier.classify()` return type

`MessageType` → `SpeechActResult`

**Callers:** `OversightGateService.evaluate()` — one call site, mechanical migration.

**Implementors:** any `@Alternative @Priority(1)` implementation of `SpeechActClassifier`
must be updated to return `SpeechActResult`. No external implementors exist in this codebase,
but this must be noted as a deployment contract change.

### Breaking: `SpeechActContext` — drop `actionType`

`record SpeechActContext(String agentId, String output)` — the `actionType` field was always
`null` (Phase 1 placeholder). The type is now derived from the output content.

**Callers:** `OversightGateService.evaluate()` — drops the third constructor argument.

---

## Detection Tiers

`SpeechActDetection.detect(output)` trims the input before matching at all tiers. Trimming
is the responsibility of this method, not callers.

### Tier 1 — Structured JSON

Format:
```json
{"type": "DONE", "content": "Boiler serviced. Pressure nominal."}
```

Rules:
- Triggered when **trimmed** output starts with `{`
- Outputs wrapped in markdown code fences (` ```json `) do NOT trigger Tier 1 — the
  trimmed content starts with a backtick. Agents must output bare JSON, not fenced JSON.
  This constraint is documented in the SKILL.md protocol.
- Required fields: `type` (string, non-null), `content` (string, non-null)
- A `content` field present with value `null` is treated as missing — fall through to Tier 2
- `type` matched case-insensitively against `MessageType` names; unknown value falls through
- The JSON parser must be configured to reject trailing content (strict mode). Lenient parsing
  that silently ignores trailing text would break the Tier 1 fall-through guarantee.
  Jackson: configure `DeserializationFeature.FAIL_ON_TRAILING_TOKENS = true`, or parse into
  a `JsonNode` and check that the full input is consumed.
- Any parse failure or missing/null required field falls through silently to Tier 2
- Additional JSON fields are ignored

### Tier 2 — Bracket Prefix

Format:
```
[STATUS] Boiler pressure: 1.2 bar, within normal range.
[DECLINE] Cannot access the external boiler API from this session.
[STATUS]: Still working — colon after bracket is accepted.
```

Rules:
- Applied to **trimmed** output
- Pattern: `^\[([A-Za-z]+)\]:?\s*` — optional trailing colon, optional whitespace
- `type` matched case-insensitively against `MessageType` names; unknown value falls through
- `content` = everything after the bracket group plus optional colon and whitespace
- `[DONE]` with nothing after → `content = ""` (empty string; valid, Qhorus accepts empty
  content on any message type)
- No match or unknown type falls through to Tier 3

### Tier 3 — Fallback

`DefaultSpeechActClassifier`: `SpeechActResult(STATUS, rawOutput != null ? rawOutput : "", FALLBACK)`

**Fallback is STATUS, not DONE.** Rationale: a false completion (Watchdog disarmed, case
step proceeds incorrectly) is invisible and unrecoverable without manual intervention. A
stuck commitment (Watchdog fires, escalates to human) is visible and recoverable. In a
system where agents are trained to signal explicitly, a missing signal is most conservatively
interpreted as "still working", not "done". See §Fallback Design Decision.

When Tier 3 fires, `DefaultSpeechActClassifier` emits:
```
log.infof("SpeechActDetection: no explicit signal from agentId=%s — STATUS fallback applied", ctx.agentId());
```
This makes unexplained Watchdog escalations debuggable. Without this log, "why did the
Watchdog fire?" has no answer.

Future `NliSpeechActClassifier @Alternative @Priority(1)`: calls
`SpeechActDetection.detect(ctx.output())`, then ML classification via `TextClassifier`
if detection returns empty, then `SpeechActResult(STATUS, ..., FALLBACK)` if NLI confidence
is below threshold.

---

## Fallback Design Decision

Changing the fallback from DONE to STATUS is a deliberate break from Phase 1 behaviour.

**Phase 1 rationale:** agents had no way to signal speech act type; every completion was
DONE by convention. The DONE fallback was correct.

**Phase 2/3 rationale:** agents are now trained to signal explicitly. A missing signal is
no longer a known-safe default — it is a protocol violation. The two failure modes are:

| Failure mode | Fallback = DONE | Fallback = STATUS |
|---|---|---|
| Agent signals STATUS intent naturally, forgets prefix | False completion — case closes incorrectly | Watchdog fires — operator investigates |
| Agent genuinely completes, forgets prefix | Case closes correctly | Watchdog fires — unnecessary escalation |

The first failure mode (false completion) is worse. Watchdog escalation is a defined recovery
path; a silently-closed case step is not.

**SKILL.md must communicate severity.** "If no prefix is provided, CaseHub assumes DONE" is
wrong and was always wrong. The correct instruction is in §SKILL.md Updates.

### Deployment / migration note

**This is a breaking behavioral change for existing deployments.** Any case-step agent that
relies on the Phase 1 DONE fallback (does not prefix its responses) will, after this change,
produce STATUS instead of DONE — leaving commitments open indefinitely and triggering Watchdog
escalation on every agent turn.

**The code change and agent skill updates must ship together.** A phased rollout — new code
deployed first, agent SKILL.md updates second — is not safe. The window between the two
deployments will produce Watchdog escalations for every agent turn from every non-prefixed
agent.

Deployment checklist:
1. Update all active case-step agent SKILL.md files with the prefix protocol (or update
   `casehub-global/SKILL.md` if agents pick it up via ClawHub)
2. Verify agents are producing prefixed output in a non-production environment
3. Ship the code change and the skill update as a single coordinated deploy

---

## `OversightGateService` Changes

`evaluate()` classifies once and uses `result.content()` for Qhorus dispatch content in all
branches:

```java
SpeechActResult speechAct = speechActClassifier.classify(new SpeechActContext(agentId, output));
RiskDecision decision = actionRiskClassifier.classify(
    new PlannedAction(agentId, caseId, speechAct.content(), null, Map.of()));

if (decision instanceof RiskDecision.Autonomous) {
    MessageDispatch.Builder builder = MessageDispatch.builder()
        .channelId(workChannelId)
        .sender(agentId)
        .content(speechAct.content())   // ← stripped content in both branches
        .actorType(ActorType.AGENT);

    if (commandMessageId != null && correlationId != null) {
        builder.type(speechAct.type()).inReplyTo(commandMessageId).correlationId(correlationId);
    } else {
        builder.type(MessageType.STATUS);
        // ... Watchdog expiry logging unchanged
    }
    messageService.dispatch(builder.build());
} else {
    openGate(caseId, workChannelId, agentId, output, speechAct, gate);
}
```

**`openGate()` — dual content distinction:**

```java
// Audit record: raw output preserved for machine-retrievability and audit fidelity.
// The COMMAND message content is what the agent actually produced — not the parsed envelope.
messageService.dispatch(MessageDispatch.builder()
    .content(output != null ? output : "")   // ← RAW output — audit contract
    ...);

// Human-readable oversight prompt: stripped content, no JSON wrapper or brackets.
String oversightPrompt = buildOversightPrompt(agentId, speechAct.content(), gate);
```

The existing code comment was placed deliberately: `// COMMAND content = original agent
output (machine-retrievable)`. This contract is preserved. An oversight reviewer should
not see `{"type":"DONE","content":"Cancel Netflix subscription."}` as the action description;
they should see `Cancel Netflix subscription.` — but the audit record must contain the raw
output for machine-retrievability.

**`PlannedAction.description` receives `speechAct.content()` (stripped content).** This is
a documented semantic change: the risk classifier assesses the action description without
classification metadata. Since Phase 1's `DefaultActionRiskClassifier` is always-AUTONOMOUS,
this has no current effect. When `casehub-engine-api` ships `ActionRiskClassifier` (engine#402),
the real classifier will receive stripped content. This must be documented on the
`ActionRiskClassifier` SPI javadoc: "The description is the agent's intended action, with
any speech-act type prefix or JSON envelope stripped."

---

## SKILL.md Updates

### `casehub-global/SKILL.md` — Case step responses section

```markdown
## Case step responses

**When CaseHub invokes you as a case step (you received a COMMAND and are replying via
the deliver:webhook path), you MUST prefix every response with the speech act type.**
Omitting a prefix is treated as an in-progress update — CaseHub will leave the commitment
open and the Watchdog will escalate, even if you intended to signal completion.

**JSON format (preferred — machine-readable):**
Do not wrap in markdown code fences. Bare JSON only.
{"type": "DONE", "content": "Your response here."}

**Bracket prefix format (simpler alternative):**
[DONE] Your response here.
[STATUS]: colon after the bracket is also accepted.

Valid types:
- DONE — task complete; commitment resolved as fulfilled
- STATUS — still in progress; Watchdog stays armed (you can send DONE later)
- DECLINE — you cannot complete the task; commitment resolved as declined
- FAILURE — task failed with an error; commitment resolved as failed
- RESPONSE — only if you received a QUERY obligation (not a COMMAND); if in doubt, use DONE
```

**Note on scope:** `casehub-global` has `always: true` and applies to all agents, not only
case step agents. This creates noise for heartbeat and ad-hoc agents. The correct long-term
solution is to inject the speech act protocol into the COMMAND message itself (via
`OpenClawChannelBackend.post()`), so it reaches only case step agents. Filed as openclaw#28.
For this pass, casehub-global with conditional framing is the practical path.

---

## Platform cross-dependency update (future, parent#172)

When openclaw#27 ships:
```
casehub-inference-api → casehub-openclaw → casehub →
  TextClassifier — SpeechActClassifier NLI implementation (future, openclaw#27)
```

---

## Test Coverage

### Unit tests for `SpeechActDetection` (new — no Quarkus context)

| Case | Input | Expected type | Expected content | Expected tier |
|------|-------|---------------|------------------|---------------|
| JSON DONE | `{"type":"DONE","content":"ok"}` | DONE | `ok` | JSON |
| JSON STATUS lowercase | `{"type":"status","content":"working"}` | STATUS | `working` | JSON |
| JSON DECLINE | `{"type":"DECLINE","content":"can't"}` | DECLINE | `can't` | JSON |
| JSON FAILURE | `{"type":"FAILURE","content":"err"}` | FAILURE | `err` | JSON |
| JSON RESPONSE | `{"type":"RESPONSE","content":"ans"}` | RESPONSE | `ans` | JSON |
| JSON unknown type | `{"type":"ESCALATE","content":"x"}` | `Optional.empty()` | — | — |
| JSON missing content | `{"type":"DONE"}` | `Optional.empty()` | — | — |
| JSON malformed | `{broken` | `Optional.empty()` | — | — |
| JSON fenced (markdown) | ` ```json\n{...}` | `Optional.empty()` | — | — |
| JSON with trailing text | `{"type":"DONE","content":"ok"} extra` | `Optional.empty()` | — | — |
| JSON content null | `{"type":"DONE","content":null}` | `Optional.empty()` | — | — |
| Prefix DONE | `[DONE] task finished` | DONE | `task finished` | PREFIX |
| Prefix STATUS | `[status] still running` | STATUS | `still running` | PREFIX |
| Prefix no space | `[DONE]task finished` | DONE | `task finished` | PREFIX |
| Prefix with colon | `[STATUS]: progress update` | STATUS | `progress update` | PREFIX |
| Prefix empty content | `[DONE]` | DONE | `""` | PREFIX |
| Prefix unknown | `[ESCALATE] help` | `Optional.empty()` | — | — |
| Leading whitespace JSON | `  {"type":"DONE","content":"ok"}` | DONE | `ok` | JSON |
| Leading whitespace prefix | `  [STATUS] working` | STATUS | `working` | PREFIX |
| No signal | `Task is complete.` | `Optional.empty()` | — | — |
| Null input | `null` | `Optional.empty()` | — | — |
| Empty input | `""` | `Optional.empty()` | — | — |

### Unit tests for `DefaultSpeechActClassifier` (extended)

| Case | Expected type | Expected content | Expected tier |
|------|---------------|------------------|---------------|
| JSON DONE | DONE | stripped | JSON |
| Prefix DECLINE | DECLINE | stripped | PREFIX |
| No prefix (plain text) | STATUS | raw output | FALLBACK |
| Null output | STATUS | `""` | FALLBACK |
| Empty output | STATUS | `""` | FALLBACK |

### Unit tests for `OversightGateService` (extended)

**Existing tests:** update mock setup at every `thenReturn(MessageType.DONE)` site —
change to `thenReturn(new SpeechActResult(MessageType.DONE, "output text", DetectionTier.JSON))`.
These tests will not compile until updated; do not treat "existing tests pass" as a goal
without first applying the mock migration.

New tests:
- `evaluate_prefixStatus_dispatchesStatus` — `[STATUS] progress` → STATUS dispatched with inReplyTo + correlationId (ACKNOWLEDGED is the verified Qhorus consequence — see §Classifiable MessageTypes; unit test asserts dispatch arguments only)
- `evaluate_jsonDecline_dispatchesDecline` — `{"type":"DECLINE","content":"can't"}` → DECLINE dispatched with inReplyTo
- `evaluate_jsonDecline_contentStripped` — Qhorus dispatch content = `can't`, not full JSON string
- `evaluate_prefixDone_contentStripped` — Qhorus dispatch content = stripped text, not `[DONE] text`
- `evaluate_noPrefixFallback_withOpenCommitment_dispatchesStatusWithInReplyTo` — plain text output, open commitment present → STATUS dispatched with inReplyTo + correlationId (ACKNOWLEDGED is the verified Qhorus consequence; unit test asserts dispatch arguments only)
- `evaluate_noPrefixFallback_watchdogExpiredPath_dispatchesStatusWithoutInReplyTo` — plain text output, no open commitment (Watchdog expired) → STATUS dispatched without inReplyTo; warn log emitted
- `evaluate_oversightGate_commandContentIsRawOutput` — `openGate()` COMMAND dispatch content = raw `output` (e.g. full JSON string); assert content is NOT stripped
- `evaluate_oversightGate_promptUsesStrippedContent` — assert `hookClient.invoke()` prompt argument (captured via `ArgumentCaptor`) contains `speechAct.content()` and does NOT contain the raw JSON wrapper (`{"type":...}`) or bracket prefix (`[DONE]`)
- `evaluate_watchdogExpiredPath_contentIsStripped` — Watchdog-expired branch (no correlationId): STATUS dispatched with `speechAct.content()`, not raw `output`

---

## Files Changed

| File | Change |
|------|--------|
| `casehub/.../DetectionTier.java` | New enum: JSON, PREFIX, FALLBACK |
| `casehub/.../SpeechActResult.java` | New record: type, content, tier |
| `casehub/.../SpeechActDetection.java` | New public utility: JSON + prefix detection |
| `casehub/.../SpeechActClassifier.java` | Return type `MessageType` → `SpeechActResult`; breaking |
| `casehub/.../SpeechActContext.java` | Drop `actionType` field; breaking |
| `casehub/.../DefaultSpeechActClassifier.java` | Delegate to `SpeechActDetection`; STATUS fallback; Tier 3 log |
| `casehub/.../OversightGateService.java` | Use `SpeechActResult`; stripped content for dispatch and PlannedAction; raw content for COMMAND audit; add `speechAct` parameter to private `openGate()` |
| `skills/casehub-global/SKILL.md` | Add case step response protocol |
| `casehub/.../SpeechActDetectionTest.java` | New unit tests |
| `casehub/.../DefaultSpeechActClassifierTest.java` | Extended tests |
| `casehub/.../OversightGateServiceTest.java` | Extended tests |

No Flyway migrations. No REST endpoint changes. No Python or TypeScript changes.

---

## Out of Scope (issues filed)

- `NliSpeechActClassifier` implementation — openclaw#27 (gates on `casehub-neural-text` + `casehub-openclaw-inference` module)
- PLATFORM.md cross-dependency entry for `SpeechActClassifier` NLI — parent#172
- Speech act protocol injection via `OpenClawChannelBackend.post()` (removes casehub-global noise for non-case-step agents) — openclaw#28
