# Epic 6 — Bidirectional Wiring End-to-End

**Issue:** casehubio/openclaw#6
**Branch:** issue-6-bidirectional-wiring
**Date:** 2026-05-30
**Status:** Approved design (post-review revision)

---

## 1. Scope

Three deliverables:

1. **End-to-end integration test** — `@QuarkusTest` proving the full round trip: COMMAND on Qhorus work channel → `ChannelBackend.post()` → `POST /hooks/agent` → OpenClaw result → `POST /openclaw/delivery/channel/{channelId}` → DONE dispatched to Qhorus work channel.
2. **Speech act classification Phase 1** — `SpeechActClassifier` interface extracted; Phase 1 implementation always returns DONE. Groundwork for Phase 2/3 (openclaw#10).
3. **Oversight channel gate** — `ActionRiskClassifier` interface + `OversightGateService` wired and tested. Phase 1 implementation always returns AUTONOMOUS (gate never fires in production); the gate path is fully implemented and integration-tested.

---

## 2. New Cross-Repo Issues Filed During Design

| Repo | Issue | What |
|------|-------|------|
| casehubio/engine | #402 | `ActionRiskClassifier` SPI in engine-api + runtime consumption |
| casehubio/claudony | #141 | ActionRiskClassifier opportunities in Claudony workers |
| casehubio/devtown | #56 | ActionRiskClassifier opportunities in devtown |
| casehubio/clinical | #47 | ActionRiskClassifier opportunities in clinical |
| casehubio/aml | #42 | ActionRiskClassifier opportunities in AML |
| casehubio/life | #20 | ActionRiskClassifier opportunities in life |
| casehubio/claudony | #142 | Oversight channel allowedTypes — deep design question |
| casehubio/parent | #106 | Spec §7.1 update, gated on claudony#142 |
| casehubio/openclaw | #15 | Two-step dispatch window in fulfill() — Watchdog mitigates |

---

## 3. Component Map

### 3.1 New utility in `casehub/` module

**`CaseChannelNames`** (package-private)

Static utility for channel name operations shared by `OpenClawChannelBackend` and `OversightGateService`. Eliminates duplication of `extractCaseId()` logic.

```java
static UUID extractCaseId(String channelName)           // "case-{caseId}/..." → UUID or null
static String workChannelName(UUID caseId)              // "case-{caseId}/work"
static String oversightChannelName(UUID caseId)         // "case-{caseId}/oversight"
```

`OpenClawChannelBackend.extractCaseId()` delegates to this; the existing method becomes package-private and forwards.

---

### 3.2 New interfaces and types in `casehub/` module

**`ActionRiskClassifier`** (interface + sealed types + Phase 1 default)

Local placeholder for the engine-api SPI proposed in casehubio/engine#402. Contract is identical to what the engine SPI will define — migration is a pure import swap with no contract change.

```java
public interface ActionRiskClassifier {
    RiskDecision classify(PlannedAction action);
}

public record PlannedAction(
    String workerId,      // agentId — the OpenClaw agent performing the action
    UUID caseId,          // derived from work channel name at evaluate() time
    String description,   // agent's output text — what it proposes to do
    String actionType,    // null in Phase 1; Phase 2: from agent capability config (openclaw#10)
    Map<String, String> context  // empty map in Phase 1; Phase 2: parsed domain facts
) {}

public sealed interface RiskDecision permits RiskDecision.Autonomous, RiskDecision.GateRequired {
    record Autonomous() implements RiskDecision {}
    record GateRequired(String reason, boolean reversible) implements RiskDecision {}
}
```

`DefaultActionRiskClassifier`: `@ApplicationScoped`, always returns `Autonomous`. Override via `@Alternative @Priority(1)`.

Javadoc on the interface: *"Phase 1: always AUTONOMOUS. Local placeholder for casehubio/engine#402 ActionRiskClassifier SPI. When engine-api ships the SPI, replace this interface and its implementations with the engine-api import — the contract is identical by design."*

---

**`SpeechActClassifier`** (interface + Phase 1 default)

Classifies an OpenClaw agent output into a Qhorus `MessageType`. Extracted now to isolate classification logic from `OversightGateService` and provide a clean seam for Phase 2/3.

```java
public interface SpeechActClassifier {
    MessageType classify(SpeechActContext context);
}

public record SpeechActContext(
    String agentId,
    String output,
    // null in Phase 1. Phase 2: derive from agent capability config (openclaw#10).
    // Source: OpenClawCasehubConfig agent entry for agentId — capabilities are known
    // at delivery time because the agent was provisioned with them.
    String actionType
) {}
```

`DefaultSpeechActClassifier`: `@ApplicationScoped`, always returns `MessageType.DONE`.

Javadoc on the interface:
```
Phase 1 (this implementation): always DONE. Inferred from invocation context —
a COMMAND was received and this is the agent's completion response.

Phase 2 (openclaw#10): detect skill-output prefix conventions prepended by
SKILL.md instructions — e.g. "[STATUS] Boiler pressure 1.2 bar" → STATUS,
"[DECLINE] Insufficient funds" → DECLINE.

Phase 3 (openclaw#10): parse structured JSON output from skills that provide
machine-readable speech acts: {"type": "STATUS", "content": "..."}.

This interface exists now to establish the contract and isolate classification
from OversightGateService. Phase 2/3 implementations are drop-in replacements.
Override via @Alternative @Priority(1).
```

---

### 3.3 New service in `casehub/` module

**`OversightGateService`**

Owns the complete gate lifecycle. Two public methods.

`evaluate(UUID workChannelId, String agentId, String output)`:

1. Look up work channel by ID; extract `caseId` using `CaseChannelNames.extractCaseId()`
2. Build `SpeechActContext(agentId, output, null /*Phase 1*/)`; call `SpeechActClassifier.classify()` → `MessageType`
3. Build `PlannedAction(agentId, caseId, output, null, Map.of())`; call `ActionRiskClassifier.classify()` → `RiskDecision`
4. **If `Autonomous`:** `MessageService.dispatch(classifiedType, sender=agentId)` to work channel. Return.
5. **If `GateRequired(reason, reversible)`:**
   - `gateId = UUID.randomUUID()`
   - Find oversight channel: `channelService.findByName(CaseChannelNames.oversightChannelName(caseId))`
     - If `Optional.empty()`: log error ("oversight channel not found for caseId={}"), return (fail-open). This should not occur — `OpenClawCaseChannelProvider.openChannel()` creates all three LAYOUT channels at case open, so the oversight channel always pre-exists when a gate fires.
   - Build oversight prompt (see §3.3.1)
   - `MessageService.dispatch(COMMAND, correlationId=gateId, sender=agentId, content=oversightPrompt)` → oversight channel
     → Qhorus auto-opens `Commitment(correlationId=gateId)`
   - `oversightDeliveryUrl = config.delivery().baseUrl() + "/openclaw/delivery/oversight/" + gateId`
   - Read `oversightAgentId` from `OpenClawCasehubConfig.oversight().agentId()` (defaults to `agentId` if unconfigured — Phase 1 acceptable since gate never fires)
   - `hookClient.invoke(oversightAgentId, oversightPrompt, model, timeout, oversightDeliveryUrl)`
6. Wrap all logic in try/catch: log error, return. Gate must never throw to the resource.

`fulfill(UUID gateId, String rawOutput)`:

1. `approved = rawOutput.trim().toLowerCase().split("\\s+")[0].equals("approved")` — first word must be exactly "approved"; anything else is rejected
2. `commitmentStore.findByCorrelationId(gateId.toString())` → `Optional<Commitment>`
   - If empty: log warn ("fulfill() called for unknown gateId={}"), return (fail-open — duplicate delivery or unknown gate). The Commitment is durable across restarts; empty means the gate was never opened or was already fulfilled.
3. `channelService.findById(commitment.channelId)` → oversight channel
4. `caseId = CaseChannelNames.extractCaseId(oversightChannel.name)`
5. `workChannel = channelService.findByName(CaseChannelNames.workChannelName(caseId))`
   - If empty: log error, return (fail-open)
6. **If `approved`:**
   - `MessageService.dispatch(RESPONSE, correlationId=gateId, sender="openclaw-gate")` → oversight channel → Commitment auto-fulfills
   - `MessageService.dispatch(DONE, sender="openclaw-gate")` → work channel
7. **If `!approved`:**
   - `MessageService.dispatch(DECLINE, correlationId=gateId, sender="openclaw-gate")` → oversight channel → Commitment auto-declines
   - `MessageService.dispatch(DECLINE, sender="openclaw-gate")` → work channel

Note: the two dispatches in step 6/7 are not atomic — see openclaw#15 for the narrow crash window between them. Watchdog is the current mitigation.

**Commitment close verified (MessageService.java L234–246):** `dispatch(RESPONSE, correlationId)` calls `commitmentService.fulfill()` and `dispatch(DECLINE, correlationId)` calls `commitmentService.decline()` automatically. No explicit close call needed.

**Why CommitmentStore rather than an in-memory map:** The Commitment is persisted in the Qhorus database. After a JVM restart, `commitmentStore.findByCorrelationId(gateId)` succeeds — the gate survives restart. An in-memory map does not. `CommitmentStore` is a stable public `*Store` interface per the qhorus-service-store-seam protocol.

#### 3.3.1 Oversight prompt

```
OpenClaw agent "{agentId}" proposes the following action:

{output}

Reason for oversight: {reason}
[If !reversible: "⚠️ This action cannot be undone once approved."]

Reply with "approved" to proceed or "rejected" to decline.
```

#### 3.3.2 Approval detection

```java
boolean approved;
if (rawOutput == null || rawOutput.isBlank()) {
    log.warnf("fulfill() received null/blank output for gateId=%s — treating as rejected", gateId);
    approved = false;
} else {
    approved = rawOutput.trim().toLowerCase().split("\\s+")[0].equals("approved");
}
```

First word must be exactly "approved". Null or blank output defaults to rejected — a malformed OpenClaw callback should not silently approve an irreversible action. This avoids false positives on "I haven't approved this", "not approved", "unapproved". Anything that is not first-word "approved" is treated as rejected. Phase 2 can use structured output (openclaw#10).

---

### 3.4 OpenClawCasehubConfig extension

Add `oversight()` config group:

```java
interface Oversight {
    Optional<String> agentId();  // defaults to work agent if absent — Phase 1 acceptable
}
Oversight oversight();
```

`OversightGateService` reads `config.oversight().agentId().orElse(workAgentId)`. This decouples the oversight delivery agent from the work agent without changing `evaluate()`'s signature.

---

### 3.5 New in `core/` module

**`OpenClawHookClient.invoke()` overload**

```java
public void invoke(String agentId, String message, String model, int timeoutSeconds, String deliveryUrl)
```

Reads `sessionKey` from the registered session; uses the caller-supplied `deliveryUrl` as the `to` field. Existing `invoke(agentId, message, model, timeout)` delegates to this with `session.webhookUrl()`.

---

### 3.6 New in `app/` module

**`OpenClawOversightDeliveryResource`**

```
POST /openclaw/delivery/oversight/{gateId}
```

Receives OpenClaw's callback when the human responds. Validates gateId (400 on malformed UUID). Delegates `rawOutput` to `OversightGateService.fulfill(gateId, rawOutput)`. Always returns 200.

**`OpenClawOversightDeliveryPayload`**

```java
public record OpenClawOversightDeliveryPayload(String agentId, String output) {}
```

Structurally identical to `OpenClawDeliveryPayload` today, but kept separate: these represent semantically different events (agent task result vs. human governance decision) and are expected to diverge as oversight responses gain delivery platform metadata (channel, timestamp, responder identity).

---

### 3.7 Changed

**`OpenClawDeliveryResource`** — remove direct `MessageService.dispatch()` call; delegate to `OversightGateService.evaluate(channelId, agentId, output)`. Resource stays thin: validate channelId, check channel exists, delegate, return 200.

**`OpenClawCaseChannelProvider.LAYOUT`** — oversight channel `allowedTypes` → `null`.

Comment: *"Minimum types needed for the oversight gate: COMMAND (gate opens Commitment), RESPONSE (approved — closes Commitment), DECLINE (rejected — closes Commitment). Set to null rather than 'COMMAND,RESPONSE,DECLINE' because the oversight conversation may also need QUERY (human asks for context), STATUS (agent clarifies), and EVENT (telemetry). Pending casehubio/claudony#142 — update to explicit list if Claudony's design resolution constrains it."*

**`OpenClawChannelBackend.extractCaseId()`** — delegate to `CaseChannelNames.extractCaseId()`.

---

## 4. Data Flow

### Path A — Autonomous (Phase 1: always this path)

```
Qhorus work channel COMMAND
  → ChannelGateway.fanOut()
  → OpenClawChannelBackend.post()
      hookClient.registerSession(agentId, sessionKey, "/openclaw/delivery/channel/{workChannelId}")
      hookClient.invoke(agentId, content, model, timeout)
  → OpenClaw: POST /hooks/agent → LLM runs
  → OpenClaw: POST /openclaw/delivery/channel/{workChannelId}
  → OpenClawDeliveryResource
      OversightGateService.evaluate(workChannelId, agentId, output)
          SpeechActClassifier → DONE
          ActionRiskClassifier → AUTONOMOUS
          MessageService.dispatch(DONE, sender=agentId) → work channel
```

### Path B — Gate required (Phase 1: never triggered; fully wired and tested)

```
[same through OversightGateService.evaluate()]
  SpeechActClassifier → DONE
  ActionRiskClassifier → GATE_REQUIRED(reason, reversible)

  gateId = random UUID
  oversightChannel = channelService.findByName("case-{caseId}/oversight")  // guaranteed to exist
  MessageService.dispatch(COMMAND, correlationId=gateId, sender=agentId) → oversight channel
    [Qhorus auto-opens Commitment]
  hookClient.invoke(oversightAgentId, oversightPrompt, model, timeout, "/openclaw/delivery/oversight/{gateId}")
    → OpenClaw delivers gate question to human via WhatsApp/Telegram

  [human responds on WhatsApp/Telegram]

  → OpenClaw: POST /openclaw/delivery/oversight/{gateId}  with {agentId, output}
  → OpenClawOversightDeliveryResource.deliver(gateId, payload)
      OversightGateService.fulfill(gateId, payload.output())
          approved = first word of output == "approved"
          commitment = commitmentStore.findByCorrelationId(gateId)
          oversightChannel → caseId → workChannel

          if approved:
            dispatch(RESPONSE, correlationId=gateId, sender="openclaw-gate") → oversight [Commitment fulfilled]
            dispatch(DONE, sender="openclaw-gate") → work channel
          else:
            dispatch(DECLINE, correlationId=gateId, sender="openclaw-gate") → oversight [Commitment declined]
            dispatch(DECLINE, sender="openclaw-gate") → work channel
```

---

## 5. Testing

### Unit tests (`casehub/`, `core/`)

| Test class | Key cases |
|-----------|-----------|
| `CaseChannelNamesTest` | extractCaseId: valid, with-suffix, non-case → null; workChannelName, oversightChannelName round-trip |
| `DefaultSpeechActClassifierTest` | Always DONE; null-safe on all SpeechActContext fields |
| `DefaultActionRiskClassifierTest` | Always AUTONOMOUS for any PlannedAction |
| `OversightGateServiceTest` | evaluate→autonomous dispatches DONE with sender=agentId; evaluate→gateRequired opens Commitment (COMMAND, correlationId=gateId, sender=agentId) and invokes OpenClaw with oversight URL; evaluate→oversightChannel absent fails open; evaluate→classifier throws fails open; fulfill→approved: RESPONSE+DONE with correct senders; fulfill→rejected: DECLINE+DECLINE; fulfill→unknown gateId (empty commitment) fails open; fulfill→rawOutput=null treated as rejected (no NPE); fulfill→rawOutput="" treated as rejected; fulfill→rawOutput first-word edge cases ("approved here", "not approved", "APPROVED", "rejected") |
| `OpenClawHookClientTest` (extended) | New overload uses explicit deliveryUrl, reads sessionKey from session |

### `@QuarkusTest` — end-to-end (`app/`)

**`BidirectionalWiringIT`**

Test setup:
```java
@BeforeEach
void setup() {
    Channel work = channelService.create(CaseChannelNames.workChannelName(caseId), ...);
    Channel oversight = channelService.create(CaseChannelNames.oversightChannelName(caseId), ...);
    workChannelId = work.id;
    registry.register("test-agent", caseId, "test-session-key");
    backend.onChannelInitialised(new ChannelInitialisedEvent(workChannelId, work.name));
}
```

Gate path tests use a test-profile `ActionRiskClassifier` override:
```java
// Nested static class in BidirectionalWiringIT — active in gate-path test methods
@Alternative @Priority(1) @ApplicationScoped
static class GateRequiredClassifier implements ActionRiskClassifier {
    @Override public RiskDecision classify(PlannedAction action) {
        return new RiskDecision.GateRequired("test oversight", false);
    }
}
```

Mechanism: `@QuarkusTestProfile` with `getEnabledAlternatives()` returning `GateRequiredClassifier.class`, activated via `@TestProfile` on gate-path test methods (or a separate inner test class with `@TestProfile`).

| Test | What it proves |
|------|---------------|
| `command_invokes_openclaw_and_delivers_done` | Full round trip: COMMAND → WireMock `/hooks/agent` receives correct body (agentId, message, deliver=webhook, to=work delivery URL) → POST to delivery endpoint → DONE on work channel with correct content and sender |
| `deliver_with_gate_required_posts_to_oversight_and_invokes_openclaw_again` | Gate path: WireMock receives second `/hooks/agent` with oversight delivery URL in `to`; COMMAND on oversight channel with correlationId; after POST to `/openclaw/delivery/oversight/{gateId}` with output="approved": RESPONSE on oversight, DONE on work |
| `deliver_gate_rejected_dispatches_decline` | output="rejected" → DECLINE on oversight (correlationId matches), DECLINE on work |
| `deliver_unknown_channel_returns_404` | |
| `deliver_invalid_uuid_returns_400` | |
| `oversight_delivery_malformed_gateId_returns_400` | |
| `oversight_delivery_unknown_gateId_returns_200` | fail-open: empty commitmentStore result, no dispatch, 200 |

**`OpenClawOversightDeliveryResourceTest`** — thin: delegates, always 200, malformed UUID → 400.

---

## 6. Deferred Concerns

| Concern | Tracked |
|---------|---------|
| ActionRiskClassifier SPI in engine-api | casehubio/engine#402 |
| Oversight channel allowedTypes final design | casehubio/claudony#142 |
| Spec §7.1 table update | casehubio/parent#106 |
| Speech act classification Phase 2/3 (incl. actionType source) | casehubio/openclaw#10 |
| OpenClaw webhook payload field name verification | casehubio/openclaw#11 |
| ChannelContextWindowService.closeCase(caseId) cleanup | casehubio/openclaw#13 |
| Two-step dispatch atomicity in fulfill() | casehubio/openclaw#15 |
