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

**qhorus runtime — `MessageService`:** When constructing `OutboundMessage` from a
`MessageDispatch`, include `dispatch.target()`. The exact construction site is in
`MessageService` where it builds the `OutboundMessage` before calling `fanOut()`.

**qhorus runtime — `ChannelGateway.deliverRemote()`:** When constructing `OutboundMessage`
from a stored `Message` for AT_LEAST_ONCE redelivery, include `msg.target()`:

```java
// Before
OutboundMessage outbound = new OutboundMessage(UUID.randomUUID(),
    msg.sender(), msg.messageType(), msg.content(), msg.correlationId(),
    msg.inReplyTo(), msg.actorType(), msg.artefactRefs());

// After
OutboundMessage outbound = new OutboundMessage(UUID.randomUUID(),
    msg.sender(), msg.messageType(), msg.content(), msg.correlationId(),
    msg.inReplyTo(), msg.actorType(), msg.artefactRefs(), msg.target());
```

**Backward compatibility:** Adding a field to a record is a binary-incompatible change.
All callers that construct `OutboundMessage` directly (tests, other backends) need updating.
This is acceptable — pre-release platform, no external consumers. Search for all
`new OutboundMessage(` call sites across the platform.

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
// Existing 6-param stays abstract — all current providers implement it
void postToChannel(CaseChannel channel, String from, String content,
    MessageType type, String correlationId, String deadline);

// New 7-param is a default method — delegates to 6-param, dropping target
default void postToChannel(CaseChannel channel, String from, String content,
    MessageType type, String correlationId, String deadline, String target) {
    postToChannel(channel, from, content, type, correlationId, deadline);
}
```

The engine calls the 7-param. Providers that haven't been updated inherit the default
(target is silently dropped). Providers that want `target` override the 7-param.

**engine-api — `ReactiveCaseChannelProvider`:** Same pattern with `Uni<Void>` return.

**engine runtime — `WorkerScheduleEventHandler`:** After provisioning, the engine stores the
`ProvisionResult.workerId()` and passes it as `target` when calling the 7-param
`postToChannel()`.

```java
// Pseudocode — engine dispatch path
ProvisionResult result = provisioner.provision(capabilities, context);
String target = result.workerId();  // may be null for no-op provisioners
channelProvider.postToChannel(channel, sender, content, COMMAND,
    correlationId, deadline, target);
```

**Backward compatibility:** The 6-param method stays abstract — existing providers compile
unchanged. The 7-param is a new default method — invisible to providers that don't override
it. The engine switches to calling the 7-param; un-updated providers silently drop the
target via the default delegation.

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

The 6-param override is removed — the default method in the SPI handles backward compatibility.

**`ReactiveOpenClawCaseChannelProvider`:** Same — override 7-param, remove 6-param.

**`OpenClawChannelBackend.post()`:**

```java
@Override
public void post(final ChannelRef channel, final OutboundMessage message) {
    if (message.type() != MessageType.COMMAND) return;

    final UUID caseId = extractCaseId(channel.name());
    if (caseId == null) return;

    // Route by explicit target (set by engine via postToChannel)
    String agentId = message.target();

    // Fall back to registry lookup when target is null
    // (backward compat: old engine versions, COMMANDs without targeting)
    if (agentId == null) {
        agentId = registry.findAgentId(caseId).orElse(null);
    }

    if (agentId == null) {
        log.debugf("No OpenClaw agent for caseId=%s — ignoring COMMAND on %s",
                   caseId, channel.name());
        return;
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
  - COMMAND with `target=null` falls back to `findAgentId()` (backward compat)
  - COMMAND with `target` pointing to an agent not in registry → logged, no-op
  - Multi-agent case: two agents registered, COMMAND with `target="agentA"` routes correctly
  - Non-COMMAND messages still ignored regardless of `target`
- **Unit — `OpenClawCaseChannelProviderTest`:**
  - `postToChannel()` with `target` sets `.target()` on `MessageDispatch`
  - `postToChannel()` with null `target` sets no `.target()` on `MessageDispatch`
- **Unit — `OpenClawWorkerProvisionerTest`:**
  - `provision()` returns `ProvisionResult.withWorker(agentId)`

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

## What this does NOT do

- **Parallel provisioner dispatch** — the engine still provisions workers sequentially. True
  parallel dispatch is an engine concern outside this scope.
- **Claudony update** — Claudony's provider/backend need the same changes as openclaw's. Filed
  separately; this spec covers openclaw only.
- **Remove `findAgentId()` fallback** — kept for backward compatibility with engine versions
  that don't set `target`. Can be removed once all engine deployments populate `target`.
