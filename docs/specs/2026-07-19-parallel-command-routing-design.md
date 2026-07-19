# Parallel COMMAND Routing for Multi-Agent Cases

**Issue:** openclaw#70, qhorus#370, engine#758
**Date:** 2026-07-19
**Status:** Design

---

## Problem

When multiple OpenClaw agents are active on the same case (1:N, enabled by openclaw#63),
`OpenClawChannelBackend.post()` cannot determine which agent a COMMAND targets. The engine
knows the intended recipient at dispatch time, but this information is lost before it reaches
the backend.

### Root cause: four gaps in the dispatch chain

1. **`ProvisionResult`** returns only `causedByEntryId` — the engine doesn't learn the
   physical agent identity that the provisioner selected.

2. **`CaseChannelProvider.postToChannel()`** has no `target` parameter — even if the engine
   knew the workerId, it couldn't pass it to the provider.

3. **`OutboundMessage`** has no `target` field — `MessageDispatch` and the persisted `Message`
   entity both carry `target`, but it's dropped when constructing `OutboundMessage` for backend
   delivery.

4. **Nobody sets `target` on COMMANDs** — `MessageDispatch.target` is only validated/required
   for HANDOFF. COMMAND dispatches always leave it null.

### Current (broken) routing

```
Engine provisions agentA, agentB for caseX
Engine dispatches COMMAND₁ (intended for agentA)
  → postToChannel(channel, "engine", content, COMMAND, correlationId, deadline)
  → MessageDispatch.target = null
  → OutboundMessage (no target field)
  → ChannelBackend.post() calls registry.findAgentId(caseId)
  → Returns arbitrary agent — may be agentB ← WRONG
```

---

## Design

### Change 1: `OutboundMessage` gains `target` (casehub-qhorus)

**qhorus-api:** Add `String target` to the `OutboundMessage` record.

```java
// Before
public record OutboundMessage(UUID messageId, String sender, MessageType type,
    String content, String correlationId, Long inReplyTo,
    ActorType senderActorType, List<ArtefactRef> artefactRefs) {}

// After
public record OutboundMessage(UUID messageId, String sender, MessageType type,
    String content, String correlationId, Long inReplyTo,
    ActorType senderActorType, List<ArtefactRef> artefactRefs,
    String target) {}
```

**qhorus runtime — all `OutboundMessage` construction sites:** Every site that creates
an `OutboundMessage` must include `target`. There are six production sites:

| # | Class | Path | Context |
|---|-------|------|---------|
| 1 | `MessageService` | LAST_WRITE `fanOut()` | Overwrite-then-fanOut for LAST_WRITE channels |
| 2 | `MessageService` | normal `fanOut()` | Standard dispatch path |
| 3 | `ChannelGateway` | `deliverRemote()` | Remote/cluster delivery to non-AT_LEAST_ONCE backends |
| 4 | `DeliveryBatchExecutor` | `toOutbound()` | **Primary AT_LEAST_ONCE delivery** — cursor-based batch pump |
| 5 | `ReactiveMessageService` | `OverwriteResult` fanOut | Reactive LAST_WRITE path |
| 6 | `ReactiveMessageService` | `FullResult` fanOut | Reactive standard dispatch path |

Sites 1–2 construct from `MessageDispatch` → use `dispatch.target()`.
Sites 3–4 construct from a stored `Message` → use `msg.target()`.
Sites 5–6 construct from `MessageDispatch` → use `dispatch.target()`.

Note: `DeliveryBatchExecutor.toOutbound()` (site 4) is the primary delivery path for
backends declaring `AT_LEAST_ONCE` (including `OpenClawChannelBackend`). The batch pump
reads persisted messages and calls `backend.post()` via this helper. If this site omits
`target`, every AT_LEAST_ONCE delivery loses routing information.

`ChannelGateway.deliverRemote()` (site 3) handles remote/cluster fan-out to backends
that do NOT use AT_LEAST_ONCE delivery. It explicitly skips AT_LEAST_ONCE backends.

**Backward compatibility:** Adding a field to a record is a binary-incompatible change.
All callers that construct `OutboundMessage` directly (tests, other backends) need updating.
This is acceptable — pre-release platform, no external consumers.

### Change 2: `ProvisionResult` returns `workerId` (casehub-engine)

**engine-api:** Add `String workerId` to `ProvisionResult`.

```java
// Before
public record ProvisionResult(UUID causedByEntryId) {
    public static ProvisionResult empty() { return new ProvisionResult(null); }
}

// After
public record ProvisionResult(UUID causedByEntryId, String workerId) {
    public static ProvisionResult empty() { return new ProvisionResult(null, null); }
    public static ProvisionResult withWorker(String workerId) {
        return new ProvisionResult(null, workerId);
    }
}
```

**Provisioner implementations return workerId:**
- `OpenClawWorkerProvisioner.provision()` → `ProvisionResult.withWorker(agentId)`
- `ReactiveOpenClawWorkerProvisioner` → same
- Claudony provisioners → same (separate issue)
- `@DefaultBean` no-op → `ProvisionResult.empty()` (unchanged)

**Backward compatibility:** Existing callers that call `ProvisionResult.empty()` continue
to work. The engine's consumer code gains access to `workerId` but is not broken by its
presence. Provisioners that return `empty()` simply have null workerId — the engine treats
this as "no explicit target" and falls back to existing behavior.

### Change 3: `postToChannel()` gains `target` (casehub-engine)

**engine-api — `CaseChannelProvider`:**

```java
// Before: 6-param abstract
void postToChannel(CaseChannel channel, String from, String content,
    MessageType type, String correlationId, String deadline);

// After: 7-param abstract — target replaces the implicit routing
void postToChannel(CaseChannel channel, String from, String content,
    MessageType type, String correlationId, String deadline, String target);

// 3-param convenience default updated to delegate to 7-param
default void postToChannel(CaseChannel channel, String from, String content) {
    postToChannel(channel, from, content, null, null, null, null);
}
```

No default method shim. Making `target` part of the abstract signature forces every
implementation to be explicit about routing — an un-updated provider fails at compile time.
A default method that silently drops `target` is exactly the category of bug this spec fixes.

Implementations to update:
- `OpenClawCaseChannelProvider` — sets `target` on `MessageDispatch` (this spec)
- `NoOpCaseChannelProvider` — add `target` parameter, ignore it
- `NoOpReactiveCaseChannelProvider` — same
- Claudony providers — covered by wsp-casehub-claudony#1

**engine-api — `ReactiveCaseChannelProvider`:** Same change — 7-param abstract with
`Uni<Void>` return. No default method.

**engine runtime — `WorkerScheduleEventHandler.dispatchCommand()`:** Currently calls the
6-param `postToChannel()` and has no access to the provisioner's result. Provisioning
happens earlier in the engine lifecycle (before `WorkerScheduleEvent` is published).

The engine must thread `ProvisionResult.workerId()` from the provisioning step to
`dispatchCommand()`. Concrete options (engine-internal design, resolved in engine#758):
- Store `workerId` on the engine's worker state entity (keyed by worker name), persisted
  so it survives restarts. `dispatchCommand()` reads it from the entity.
- Carry `workerId` on `WorkerScheduleEvent` from the provisioning caller.

Either way, `dispatchCommand()` calls the 7-param `postToChannel()` with the stored
`workerId` as `target`. For no-op provisioners (`ProvisionResult.empty()`), `target`
is null — the backend handles this via single-agent fallback.

**Breaking change:** The 6-param abstract is removed. All implementations and callers
must update to the 7-param signature. This is a pre-release platform with no external
consumers — compile-time breakage is the correct forcing function.

### Change 4: Provider sets `target`, backend reads it (casehub-openclaw)

**`OpenClawCaseChannelProvider.postToChannel()` (7-param override):**

```java
@Override
public void postToChannel(CaseChannel channel, String from, String content,
                           MessageType type, String correlationId,
                           String deadline, String target) {
    MessageType effectiveType = type != null ? type : MessageType.STATUS;
    messageService.dispatch(MessageDispatch.builder()
            .channelId(UUID.fromString(channel.id()))
            .sender(from)
            .type(effectiveType)
            .content(content)
            .correlationId(correlationId)
            .deadline(deadline != null ? Instant.parse(deadline) : null)
            .target(target)
            .actorType(ActorType.AGENT)
            .build());
}
```

The 6-param override is removed — the abstract signature is now 7-param.

**`ReactiveOpenClawCaseChannelProvider`:** Same — implement 7-param, remove 6-param.

**`OpenClawChannelBackend.post()`:**

```java
@Override
public void post(final ChannelRef channel, final OutboundMessage message) {
    if (message.type() != MessageType.COMMAND) return;

    final UUID caseId = extractCaseId(channel.name());
    if (caseId == null) return;

    String agentId = message.target();

    if (agentId != null) {
        // Validate target is registered for this case — prevents cross-case
        // misrouting when an agent is registered on multiple cases
        if (!registry.findAgentIds(caseId).contains(agentId)) {
            log.warnf("Target agent %s not registered for caseId=%s — "
                    + "ignoring COMMAND on %s", agentId, caseId, channel.name());
            return;
        }
    } else {
        // No explicit target — deterministic fallback for single-agent cases only
        Set<String> agents = registry.findAgentIds(caseId);
        if (agents.isEmpty()) {
            log.debugf("No OpenClaw agent for caseId=%s — ignoring COMMAND on %s",
                       caseId, channel.name());
            return;
        }
        if (agents.size() > 1) {
            log.errorf("Multiple agents registered for caseId=%s but COMMAND has "
                    + "no target — cannot route. Engine must set target for "
                    + "multi-agent cases (openclaw#70).", caseId);
            return;
        }
        agentId = agents.iterator().next();
    }

    // ... existing session key lookup, webhook URL, invocation
}
```

**`OpenClawWorkerProvisioner.provision()`:** Returns `ProvisionResult.withWorker(agentId)`.

---

## Data flow — after fix

```
Engine                    CaseChannelProvider         MessageService      ChannelGateway       ChannelBackend
  │                           │                          │                    │                    │
  │─ provision() ────────────▶│                          │                    │                    │
  │◀─ ProvisionResult ───────│                          │                    │                    │
  │   (workerId="agentA")     │                          │                    │                    │
  │                           │                          │                    │                    │
  │─ postToChannel() ────────▶│                          │                    │                    │
  │   (target="agentA")       │                          │                    │                    │
  │                           │─ dispatch(MessageDispatch)                    │                    │
  │                           │   target="agentA" ──────▶│                    │                    │
  │                           │                          │─ persist(Message)  │                    │
  │                           │                          │   target="agentA"  │                    │
  │                           │                          │─ fanOut(OutboundMsg)                    │
  │                           │                          │   target="agentA" ▶│                    │
  │                           │                          │                    │─ post(channel,msg) │
  │                           │                          │                    │   target="agentA" ▶│
  │                           │                          │                    │                    │─ route to agentA ✓
```

---

## Implementation order

The changes have a dependency chain. Build bottom-up:

1. **qhorus-api** — `OutboundMessage` gains `target` field
2. **qhorus runtime** — `MessageService` and `ChannelGateway.deliverRemote()` populate `target`
3. **engine-api** — `ProvisionResult.workerId`, `CaseChannelProvider.postToChannel()` 7-param
4. **engine runtime** — `WorkerScheduleEventHandler` passes `target`
5. **openclaw** — provider overrides 7-param, backend reads `target`, provisioner returns workerId

Steps 1-2 and 3-4 can be done in parallel (different repos). Step 5 depends on both.

After all steps, `mvn install` in dependency order: qhorus → engine → openclaw.

---

## Testing

### qhorus

- **Unit:** `OutboundMessage` construction includes target; verify `ChannelGateway.deliverRemote()`
  populates target from stored `Message`.
- **Existing tests:** All `new OutboundMessage(...)` call sites updated with the additional
  `target` parameter (null where not applicable).

### engine

- **Unit:** `ProvisionResult.withWorker("agentA")` carries workerId.
- **Unit:** `CaseChannelProvider` default method delegates 6-param to 7-param with null target.
- **Integration:** `WorkerScheduleEventHandler` dispatches COMMAND with `target` set from
  `ProvisionResult.workerId()`.

### openclaw

- **Unit — `OpenClawChannelBackendTest`:**
  - COMMAND with `target="finance-agent"` routes to `finance-agent` (not arbitrary)
  - COMMAND with `target=null`, single agent registered → falls back to that agent
  - COMMAND with `target=null`, multiple agents registered → error logged, COMMAND dropped
  - COMMAND with `target` pointing to an agent not registered for this case → warn, no-op
  - COMMAND with `target` pointing to an agent registered on a DIFFERENT case → warn, no-op
  - Multi-agent case: two agents registered, COMMAND with `target="agentA"` routes correctly
  - Non-COMMAND messages still ignored regardless of `target`
- **Unit — `OpenClawCaseChannelProviderTest`:**
  - `postToChannel()` 7-param with `target` sets `.target()` on `MessageDispatch`
  - `postToChannel()` 7-param with null `target` sets no `.target()` on `MessageDispatch`
- **Unit — `OpenClawWorkerProvisionerTest`:**
  - `provision()` returns `ProvisionResult.withWorker(agentId)`
- **Integration — `OpenClawTargetRoutingIntegrationTest`:**
  - Provisions two agents for the same case via `OpenClawWorkerProvisioner`
  - Posts a COMMAND with `target` set via `OpenClawCaseChannelProvider.postToChannel()`
    (7-param)
  - Verifies the `MessageDispatch.target()` is set, the persisted `Message.target()` is
    set, and `OpenClawChannelBackend.post()` routes to the correct agent
  - Verifies a second COMMAND with the other agent's target routes correctly
  - This is an openclaw-scope integration test. Full cross-repo integration
    (engine provisioning → qhorus dispatch → openclaw routing) is a deployment
    concern validated at the system test level.

---

## Protocols checked

| Protocol | Status |
|----------|--------|
| PP-20260523-a08b97 — dispatch enforcement gate | ✅ All writes go through `MessageService.dispatch()` |
| PP-20260622-normative-layout | ✅ No channel layout changes |
| PP-20260603-d52060 — delivery always 200 | ✅ Delivery endpoints unchanged |
| PP-20260601-4fa0b2 — idempotent registration | ✅ Registration unchanged |
| PP-20260526-case-channel-message-signal | ✅ Signal path unchanged |

---

## Known edge cases

- **AT_LEAST_ONCE redelivery after agent termination:** When a target agent is terminated
  between original delivery and redelivery, the batch pump delivers the COMMAND with the
  original `target`. `OpenClawChannelBackend.post()` validates the target against the
  registry, finds the agent is no longer registered for the case, and drops the COMMAND
  (log warn, return). This is correct — the AT_LEAST_ONCE guarantee applies to backend
  delivery, not agent availability. If the agent was terminated, the engine is responsible
  for lifecycle management (retry policy, escalation, case error state). This is an engine
  concern, not a routing concern.

- **Single-agent fallback:** When `target` is null and exactly one agent is registered for
  the case, the backend falls back to that agent. This is deterministic and safe. When
  `target` is null and multiple agents are registered, the COMMAND is rejected with an
  error log. This prevents the arbitrary-agent misrouting that this spec fixes.

## What this does NOT do

- **Parallel provisioner dispatch** — the engine still provisions workers sequentially. True
  parallel dispatch is an engine concern outside this scope.
- **Claudony update** — Claudony's provider/backend need the same changes as openclaw's.
  Tracked as wsp-casehub-claudony#1; this spec covers openclaw only.
- **Engine-internal state management** — how the engine threads `workerId` from provisioning
  to dispatch is an engine-internal design detail. Tracked as engine#758.
