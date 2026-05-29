# Design: Epic 4 — CaseHub SPI Implementations

**Issue:** casehubio/openclaw#4  
**Branch:** issue-4-casehub-spi-implementations  
**Date:** 2026-05-29  
**Status:** Approved

---

## 1. Scope

Implements all CaseHub SPI interfaces in `casehub-openclaw`, completing the integration tier:

- `WorkerProvisioner` — registers OpenClaw agents as CaseHub workers
- `CaseChannelProvider` — creates and manages Qhorus channels per case
- `ChannelBackend` — delivers Qhorus COMMANDs to OpenClaw agents (Qhorus → OpenClaw direction)
- `WorkerStatusListener` — reacts to engine lifecycle events
- `OpenClawDeliveryResource` — receives OpenClaw webhook results and writes back to Qhorus (OpenClaw → Qhorus direction)

Also updates `ChannelContextWindowService` (core/) to replace `associate()` with a split `bindAgent()` / `bindChannel()` API that eliminates the coordination race between provisioner and channel provider.

---

## 2. Key Design Decision: Service-Level Join

The original `associate(agentId, Set<channelIds>)` API requires both agentId and channelIds to be known simultaneously. In the actual flow, they're produced independently by different components — the provisioner knows the agentId, the channel provider knows the channelIds. Forcing one side to gather the other's data creates a notification protocol between SPIs that has a provable (if rare) race condition.

**Fix:** Split into two independent writes. The service joins at query time.

```
WorkerProvisioner  → service.bindAgent(agentId, caseId)
CaseChannelProvider → service.bindChannel(caseId, channelId)
query(agentId) → joins agentId→caseId→channelIds internally
```

No coordination between SPIs. No shared registry. No race. This also enables a net improvement: messages that arrive on a channel after `bindChannel()` but before `bindAgent()` are captured and available on the first query.

**Residual micro-race (within design tolerance):** `provision()` calls `registry.register()` then `service.bindAgent()` — two sequential writes. If a COMMAND arrives between them, `ChannelBackend.post()` routes correctly (registry has the agentId) but `service.query()` returns `noAssociation()` for that agent turn's context injection. One missed context injection window. This is within the "intelligence, not correctness" design contract and does not require mitigation.

**Parallel agentToCase maps:** `ChannelContextWindowService` maintains its own internal `agentToCase` map (for query-time joining); `OpenClawAgentRegistry` maintains a separate `agentToCase` map (for routing). Module boundaries prevent core/ from depending on casehub/ — the duplication is the price of module independence. Cleanup must be applied symmetrically to both maps (see §4.5 and §5).

---

## 3. Component Overview

```
core/
  ChannelContextWindowService   (modified — new bindAgent/bindChannel API)

casehub/
  OpenClawAgentRegistry         (new — caseId↔agentId↔sessionKey; used by ChannelBackend + provisioner)
  OpenClawWorkerProvisioner     (new — implements WorkerProvisioner)
  OpenClawCaseChannelProvider   (new — implements CaseChannelProvider)
  OpenClawChannelBackend        (new — implements ChannelBackend)
  OpenClawWorkerStatusListener  (new — implements WorkerStatusListener)

app/
  OpenClawDeliveryResource      (new — POST /openclaw/delivery/channel/{channelId})
```

**Dependency graph (no cycles):**
```
OpenClawWorkerProvisioner     → ChannelContextWindowService, OpenClawAgentRegistry, OpenClawConfig
OpenClawCaseChannelProvider   → ChannelContextWindowService, ChannelService (Qhorus), MessageService (Qhorus)
OpenClawChannelBackend        → OpenClawAgentRegistry, OpenClawHookClient, OpenClawConfig, ChannelGateway (Qhorus)
OpenClawWorkerStatusListener  → OpenClawAgentRegistry
OpenClawDeliveryResource      → ChannelService (Qhorus), MessageService (Qhorus)
```

---

## 4. ChannelContextWindowService Changes (core/)

### 4.1 API change

Remove `associate(String agentId, Set<UUID> channelIds)`.  
Add:

```java
void bindAgent(String agentId, UUID caseId)
void bindChannel(UUID caseId, UUID channelId)
```

No other public method signatures change.

### 4.2 Internal state additions

```java
ConcurrentHashMap<String, UUID> agentToCase    // agentId → caseId
ConcurrentHashMap<UUID, Set<UUID>> caseChannels // caseId → Set<channelId> (newKeySet())
```

Existing `buffers: ConcurrentHashMap<UUID, ChannelRingBuffer>` is unchanged.

### 4.3 `bindChannel()` behaviour

Creates the ring buffer for the channelId at bind time (previously done in `associate()`):

```java
caseChannels.computeIfAbsent(caseId, id -> ConcurrentHashMap.newKeySet()).add(channelId);
buffers.putIfAbsent(channelId, new ChannelRingBuffer(config.maxMessages(), config.ttl()));
```

### 4.4 `query()` change

Two-level join:
1. `caseId = agentToCase.get(agentId)` → null → `noAssociation()`
2. `channelIds = caseChannels.getOrDefault(caseId, Set.of())` → empty → empty window (not `noAssociation()`)
3. Read from buffers for each channelId (unchanged)

**Transient state note:** If `bindAgent()` is called before any `bindChannel()` (agent provisioned before channels open), `query()` returns agent-associated with an empty channel set. The Python SDK's idle-notice logic fires when association exists but no messages are found. `lastChannelActivity` defaults to `Instant.EPOCH` — far beyond any TTL — so the SDK would inject "No channel activity in the last N minutes." This is a misleading signal (the agent isn't idle; it has no channels yet). In the normal engine flow (`CaseStartedEventHandler` opens channels before worker dispatch), this transient state lasts microseconds. It is documented here and in the implementation comment on `bindAgent()`, but does not require mitigation.

### 4.5 Cleanup — `unbindAgent(String agentId)`

Add:

```java
void unbindAgent(String agentId)
```

Removes the `agentId` entry from `agentToCase`. Does NOT cascade to `caseChannels` — the case may continue to receive messages for a short period after agent deregistration, and ring buffers are already TTL-evicted by the existing `evictExpired()` scheduler. Retaining the `caseChannels` entry until TTL expiry is correct and avoids lifecycle complexity.

Called from `OpenClawWorkerStatusListener.onWorkerCompleted()` — the engine calls this via `WorkflowExecutionCompletedHandler` on the `WORKER_EXECUTION_FINISHED` event bus address, which fires when `QhorusMessageSignalBridge` delivers the DONE signal to `CaseHubRuntime.signal()`. Cleanup is therefore automatic and does not require the delivery resource to call the listener directly.

### 4.6 Startup recovery note

`agentToCase` and `caseChannels` are in-memory — they are empty after a restart. On restart, ring buffers are also empty (no messages persisted). A restarted service returns `noAssociation()` for all agents until they are re-provisioned and their channels re-opened. This is within the design contract: the correctness layer (Qhorus) is unaffected; the intelligence layer (ChannelContextWindow) fails open on restart.

### 4.7 `agentChannels` removal

The existing `agentChannels: ConcurrentHashMap<String, Set<UUID>>` field (used by the old `associate()`) is removed.

### 4.6 Test rewrite

All existing tests for `ChannelContextWindowService` that call `associate()` are rewritten to use `bindAgent()` + `bindChannel()` pairs. New test cases added:
- `bindChannel` before `bindAgent` → messages captured and returned once agent binds
- `bindAgent` without channels → empty window (not `noAssociation()`)

---

## 5. OpenClawAgentRegistry (casehub/)

`@ApplicationScoped`. Maintains bidirectional mapping for routing.

```java
void register(String agentId, UUID caseId, String sessionKey)
void deregister(String agentId)
Optional<String> findAgentId(UUID caseId)       // for ChannelBackend routing
Optional<String> findSessionKey(String agentId) // for invoke() calls
```

Internal: `ConcurrentHashMap<String, UUID> agentToCase`, `ConcurrentHashMap<UUID, String> caseToAgent`, and `ConcurrentHashMap<String, String> agentToSessionKey`. All three are updated sequentially (not atomically — `ConcurrentHashMap` provides no cross-map transaction). A reader between the first and last write sees partial state; this window is negligible for the MVP single-agent-per-case constraint below.

**MVP constraint — one OpenClaw agent per case:** `caseToAgent` is 1:1. If the engine provisions two agents for the same case, the second `register()` silently overwrites the first — from that point, `findAgentId(caseId)` returns only one agent. This failure is silent. For MVP, exactly one OpenClaw agent per case is assumed. If violated at config time, the provisioner MUST warn loudly. Multi-agent per case support is deferred (openclaw#12 scope).

This registry is separate from `ChannelContextWindowService`'s internal `agentToCase` map. Both maintain the agentId↔caseId relation for their own consumers — unavoidable due to module boundaries (core/ cannot depend on casehub/). Cleanup must be applied symmetrically: `registry.deregister(agentId)` and `service.unbindAgent(agentId)` must both be called from `onWorkerCompleted()`.

---

## 6. OpenClawWorkerProvisioner (casehub/)

Implements blocking `WorkerProvisioner` (not reactive — nothing in `provision()` is async).

### 6.1 `provision(Set<String> capabilities, ProvisionContext context)`

1. Resolve `agentId` from `capabilities` via config (capability matching algorithm — see below)
2. `registry.register(agentId, context.caseId(), sessionKey)`
3. `service.bindAgent(agentId, context.caseId())`
4. Return `Worker(agentId, capabilityList, ctx -> Map.of())`

**Capability matching algorithm:**
- **Subset match:** an agent is a candidate if the requested capability set is a subset of (or equal to) the agent's configured capabilities. The agent must be able to handle ALL requested capabilities.
- **First match wins:** if multiple configured agents are candidates, select the first match in config-iteration order (which is alphabetical by agentId key). Log a warning — multiple agents covering the same capabilities is a misconfiguration.
- **No match:** throw `ProvisioningException("No OpenClaw agent configured for capabilities: " + capabilities)`.
- **Spanning agents:** if requested capabilities cannot be satisfied by any single agent (e.g. requesting `{finance, code-review}` when no agent has both), the no-match case fires. The engine typically provisions one capability per PlanItem binding, so this is an edge case that requires misconfigured YAML to trigger.

The `Worker` uses a no-op function holder — OpenClaw is invoked via `ChannelBackend.post()`, not the worker function. The no-op satisfies `Worker.build()`'s non-null requirement.

### 6.2 `terminate(String agentId)`

`registry.deregister(agentId)`. No HTTP call to OpenClaw — termination is declarative (the agent stops receiving commands).

### 6.3 `getCapabilities()`

Returns the union of all configured agent capabilities.

### 6.4 Error handling

Throws `ProvisioningException` if no agent is configured for the requested capabilities.

---

## 7. OpenClawCaseChannelProvider (casehub/)

Implements blocking `CaseChannelProvider`. Mirrors Claudony's implementation but uses a fixed normative layout rather than a configurable one (consolidation deferred to parent#93).

### 7.1 Channel layout

Three channels per case, hardcoded:

| Purpose | Semantic | Allowed Types |
|---|---|---|
| `work` | `AGENT_TO_AGENT` | `COMMAND,RESPONSE,DONE,DECLINE,EXPIRED` |
| `observe` | `OBSERVER` | `EVENT,QUERY,STATUS` |
| `oversight` | `HUMAN_PARTICIPANT` | `COMMAND,RESPONSE` |

### 7.2 `openChannel(UUID caseId, String purpose)`

1. `channelName = CaseChannel.channelName(caseId, purpose)` → `"case-{caseId}/{purpose}"`
2. `channel = channelService.createOrGet(channelName, purpose, semantic, allowedTypes)` — idempotent per SPI contract
3. `service.bindChannel(caseId, channel.id)` — registers the channel with ChannelContextWindow
4. Return `CaseChannel(channel.id, channel.name, purpose, "qhorus", Map.of("qhorus-name", channel.name))`

### 7.3 `postToChannel(...)`

Calls `MessageService.dispatch()` with all 6 params mapped directly. Delegates `correlationId` (String) and `deadline` (ISO-8601 String → `Instant.parse()`) per the SPI contract.

### 7.4 `closeChannel(CaseChannel channel)`

No-op. Qhorus channels are persistent.

### 7.5 `listChannels(UUID caseId)`

`channelService.findByNamePrefix("case-{caseId}/")`

`findByNamePrefix(String prefix)` is confirmed available on `ChannelService` (and `ReactiveChannelService`) — verified at `ChannelService.java:97`. It emits `LIKE 'prefix%' ESCAPE '!'` (metachar-safe, index-eligible).

---

## 8. OpenClawChannelBackend (casehub/)

Implements `ChannelBackend`. Self-registers with Qhorus via `ChannelInitialisedEvent` observation — the designed extension point for external backends (per Qhorus deep-dive). This also handles startup recovery automatically: Qhorus fires `ChannelInitialisedEvent` for all existing channels at startup, so the backend re-registers without implementing its own recovery logic.

### 8.1 Identity

```java
String backendId() → "openclaw"
ActorType actorType() → ActorType.AGENT
```

### 8.2 Backend registration

```java
@Inject ChannelGateway gateway;

void onChannelInitialised(@Observes ChannelInitialisedEvent event) {
    if (!event.channelName().startsWith(CaseChannel.CASE_CHANNEL_PREFIX)) return;
    gateway.registerBackend(event.channelId(), this, "agent");
}
```

Only registers for channels whose name starts with `"case-"` (the `CASE_CHANNEL_PREFIX` constant). Non-case channels are ignored.

### 8.3 `open()` / `close()`

Both no-op. Channel lifecycle is managed by `OpenClawCaseChannelProvider`. Registration is via `ChannelInitialisedEvent`, not via `open()`.

### 8.4 `post(ChannelRef channel, OutboundMessage message)`

**Filter:** Only `MessageType.COMMAND` triggers agent invocation. All other types are silently ignored — the message is already stored in the ring buffer by `ChannelContextWindowObserver` and in the Qhorus ledger.

**Routing:**
1. `caseId = extractCaseId(channel.name())` — parses `"case-{caseId}/{purpose}"` → UUID
2. `agentId = registry.findAgentId(caseId)` → not found → log debug, return
3. `sessionKey = registry.findSessionKey(agentId).orElseThrow()` (should always be present if agentId found)
4. `webhookUrl = config.deliveryUrlTemplate().replace("{channelId}", channel.id().toString())`
5. `hookClient.registerSession(agentId, sessionKey, webhookUrl)` — last-write-wins (not idempotent in the traditional sense). Concurrent COMMANDs for the same agent on different channels overwrite each other's `webhookUrl` in the `OpenClawSession` registry. This is safe because the `webhookUrl` is embedded in the `POST /hooks/agent` request body at `invoke()` time — OpenClaw uses the request-body URL for delivery, not a server-side lookup. Concurrent overwrites cannot corrupt in-flight deliveries.
6. `hookClient.invoke(agentId, message.content(), config.defaultModel(), config.defaultTimeout())`

**Non-throwing contract:** `OpenClawInvocationException` is caught, logged, and swallowed. `ChannelGateway.fanOut()` is non-fatal — propagated exceptions abort the entire fan-out.

### 8.5 `extractCaseId(String channelName)`

Parses `"case-{caseId}/{purpose}"` by stripping the `"case-"` prefix and taking the segment before the first `/`. Returns `null` if the format doesn't match (non-case channels should not be registered with this backend, but defensive handling is correct).

---

## 9. OpenClawWorkerStatusListener (casehub/)

Implements blocking `WorkerStatusListener`. The engine injects and calls this SPI; our implementation reacts to lifecycle notifications.

```java
void onWorkerStarted(String workerId, Map<String,String> sessionMeta):
  log.infof("OpenClaw agent started: agentId=%s", workerId)

void onWorkerCompleted(String workerId, WorkResult result):
  log.infof("OpenClaw agent completed: agentId=%s status=%s", workerId, result.status())
  registry.deregister(workerId)       // cleans up agentToCase/caseToAgent/sessionKey maps
  service.unbindAgent(workerId)       // cleans up agentToCase in ChannelContextWindowService

void onWorkerStalled(String workerId):
  log.warnf("OpenClaw agent stalled: agentId=%s", workerId)
  events.fire(new OpenClawWorkerStalledEvent(workerId))
  // Stalled agents remain registered — they continue to receive COMMAND invocations
  // until an observer explicitly deregisters them. The platform's Watchdog handles the
  // resulting hung case step by firing timeout recovery. Deregistering here would prevent
  // retry attempts; leaving it registered lets the Watchdog and engine resilience module
  // drive recovery policy.
```

`OpenClawWorkerStalledEvent` is a public record nested in the listener (matching Claudony's pattern). A future health monitor or alerting component can subscribe without modifying the listener.

**Why the engine calls this listener:** `WorkflowExecutionCompletedHandler` consumes the `WORKER_EXECUTION_FINISHED` Vert.x event bus address and calls `workerStatusListener.onWorkerCompleted()`. This event is published by `CaseHubRuntime.signal()`, which is called by `QhorusMessageSignalBridge` when a DONE message arrives on the case channel. The listener is therefore called automatically via the standard engine path — the delivery resource does not need to call it directly.

---

## 10. OpenClawDeliveryResource (app/)

REST endpoint receiving the OpenClaw → Qhorus write-back.

### 10.1 Endpoint

```
POST /openclaw/delivery/channel/{channelId}
Content-Type: application/json
```

### 10.2 Payload

```java
public record OpenClawDeliveryPayload(
    String agentId,   // WARNING: field name unverified against live API — see openclaw#11
    String output     // WARNING: field name unverified against live API — see openclaw#11
) {}
```

Both fields may need renaming to snake_case (`agent_id`, `output`) depending on what the live OpenClaw API sends. This must be verified before production use (openclaw#11).

### 10.3 Handler

1. `channelService.find(channelId)` → not found → 404
2. `messageService.dispatch(MessageDispatch.builder()` with:
   - `channelId` from path param
   - `sender` = `payload.agentId()`
   - `type` = `MessageType.DONE` (Phase 1 — always DONE; graduation tracked in openclaw#10)
   - `content` = `payload.output()`
   - `actorType` = `ActorType.AGENT`
3. On dispatch success → 200 OK
4. On dispatch failure → log error, still return 200

**Failure consequence:** A failed dispatch means the DONE message never reaches Qhorus. `QhorusMessageSignalBridge` never fires. `WorkflowExecutionCompletedHandler` is never called. The case step hangs indefinitely. The platform's Watchdog eventually fires and drives recovery via the engine resilience module. This is the deliberate Phase 1 trade-off: OpenClaw's retry contract on non-2xx webhook responses is unverified (openclaw#11). Returning 5xx risks retry storms if OpenClaw retries aggressively. Once openclaw#11 resolves the retry contract, the failure response can be changed to 5xx to give OpenClaw an explicit retry signal.

### 10.4 Auth

None. Follows the gateway topology: auth is Claudony's concern. The delivery endpoint is internal — network policy or mTLS provides isolation.

---

## 11. Configuration (app/src/main/resources/application.properties)

### 11.1 `@ConfigMapping` interface

```java
@ConfigMapping(prefix = "casehub.openclaw")
public interface OpenClawConfig {
    Map<String, AgentConfig> agents();  // keyed by agentId (e.g. "finance-agent")
    AgentDefaults agent();
    DeliveryConfig delivery();

    interface AgentConfig {
        List<String> capabilities();    // e.g. ["finance", "banking"]
        String sessionKey();            // OpenClaw session name — may differ from map key
    }

    interface AgentDefaults {
        String defaultModel();          // e.g. "claude-opus-4-5"
        int defaultTimeoutSeconds();    // e.g. 120
    }

    interface DeliveryConfig {
        String urlTemplate();           // e.g. "http://host/openclaw/delivery/channel/{channelId}"
    }
}
```

### 11.2 Agent capability mapping (example properties)

```properties
# finance-agent: session name matches the config key (common case)
casehub.openclaw.agents.finance-agent.capabilities=finance,banking
casehub.openclaw.agents.finance-agent.session-key=finance-agent

# code-review-agent: session name differs from config key (uncommon but valid)
casehub.openclaw.agents.code-review-agent.capabilities=code-review,security-review
casehub.openclaw.agents.code-review-agent.session-key=cr-agent-main
```

`session-key` is an explicit property because the OpenClaw session name (the Python SDK concept, passed as `sessionName` in `/hooks/agent`) may differ from the config map key. The example above shows this: `code-review-agent` maps to session name `cr-agent-main`.

### 11.3 Delivery URL template

```properties
casehub.openclaw.delivery.url-template=http://localhost:8080/openclaw/delivery/channel/{channelId}
casehub.openclaw.agent.default-model=claude-opus-4-5
casehub.openclaw.agent.default-timeout-seconds=120
```

### 11.4 CDI collision fix (GE-20260428-9311f8)

Required in `app/src/main/resources/application.properties`:

```properties
quarkus.arc.exclude-types=\
  io.casehub.engine.internal.worker.NoOpWorkerStatusListener,\
  io.casehub.engine.internal.worker.NoOpReactiveWorkerStatusListener,\
  io.casehub.engine.internal.worker.EmptyWorkerContextProvider,\
  io.casehub.engine.internal.worker.EmptyReactiveWorkerContextProvider,\
  io.casehub.engine.internal.worker.NoOpWorkerProvisioner,\
  io.casehub.engine.internal.worker.NoOpReactiveWorkerProvisioner,\
  io.casehub.engine.internal.worker.NoOpCaseChannelProvider,\
  io.casehub.engine.internal.worker.NoOpReactiveCaseChannelProvider
```

The exclusion list was generated for casehub-engine 0.2-SNAPSHOT (Quarkus 3.32.2). If the engine adds new no-op implementations in future versions, the startup CDI test (`@QuarkusTest` startup) will catch the new `AmbiguousResolutionException` with a clear message.

### 11.5 pom.xml changes (casehub/)

Remove `<scope>provided</scope>` from `casehub-engine` and `casehub-qhorus` — actual SPI beans are now present.

---

## 12. Testing Strategy

### 12.1 core/ — ChannelContextWindowServiceTest (rewrite)

- `bindAgent` + `bindChannel` in both orders → `query()` returns messages correctly
- `bindChannel` before `bindAgent` → messages captured during the window, returned once agent binds
- `bindAgent` without `bindChannel` → `query()` returns empty window (not `noAssociation()`)
- Neither bind → `query()` returns `noAssociation()`
- `unbindAgent()` → subsequent `query()` returns `noAssociation()`; `caseChannels` entry retained (ring buffer still writable for in-flight messages)
- `bindChannel()` twice for same (caseId, channelId) → idempotent (Set.add + putIfAbsent)
- Overflow eviction — `lastEvictionWindowSeq` set correctly (unchanged behaviour)
- TTL expiry — unchanged behaviour
- `evictExpired()` — unchanged behaviour
- Concurrent `bindAgent` and `bindChannel` calls — no exception, eventual consistency

### 12.2 casehub/ — unit tests (pure JUnit 5 + Mockito)

**OpenClawWorkerProvisionerTest:**
- `provision()` calls `bindAgent()` with correct `(agentId, caseId)`
- `provision()` calls `registry.register()` with correct params
- `provision()` with unknown capabilities → `ProvisioningException`
- `terminate()` calls `registry.deregister(agentId)`
- `getCapabilities()` returns union of all configured capabilities

**OpenClawCaseChannelProviderTest:**
- `openChannel()` calls `bindChannel()` with `(caseId, channelId)`
- `openChannel()` idempotent — same channel name returns same result without error
- `postToChannel()` calls `dispatch()` with correct params including 6-param overload
- `postToChannel()` with null `type` / `correlationId` / `deadline` → builds dispatch without those fields

**OpenClawChannelBackendTest:**
- `onChannelInitialised` with case channel → `gateway.registerBackend()` called
- `onChannelInitialised` with non-case channel → `gateway.registerBackend()` NOT called
- COMMAND message → `hookClient.invoke()` called with correct agentId and content
- Non-COMMAND message (STATUS, EVENT, DONE, RESPONSE) → `hookClient.invoke()` NOT called
- Channel with no registered agent → no-op, no exception
- `hookClient.invoke()` throws → caught, no exception propagated from `post()`
- `extractCaseId` parses `"case-{uuid}/work"` → correct UUID
- `extractCaseId` with non-case channel name → null (no-op)

**OpenClawWorkerStatusListenerTest:**
- `onWorkerCompleted()` → calls `registry.deregister(workerId)` AND `service.unbindAgent(workerId)`
- `onWorkerStalled()` → fires `OpenClawWorkerStalledEvent`; does NOT call `registry.deregister()` (explicit by design)

**OpenClawAgentRegistryTest:**
- `register()` → `findAgentId(caseId)` and `findSessionKey(agentId)` return correct values
- `deregister()` → both lookups return empty
- `findAgentId()` for unknown caseId → empty
- `findSessionKey()` for unknown agentId → empty

### 12.3 app/ — @QuarkusTest

**OpenClawDeliveryResourceTest:**
- Valid channelId, valid payload → `dispatch()` called with DONE type, returns 200
- Unknown channelId → 404
- `dispatch()` throws → still returns 200 (fail-open)
- CDI startup check — implicit via `@QuarkusTest` startup with all SPI impls present (verifies no `AmbiguousResolutionException`)

---

## 13. End-to-End Message Flow

```
Engine calls openChannel(caseId, "work")
  → OpenClawCaseChannelProvider creates Qhorus channel "case-{caseId}/work"
  → ChannelGateway.initChannel() fires ChannelInitialisedEvent
  → OpenClawChannelBackend.onChannelInitialised() → gateway.registerBackend(channelId, this, "agent")
  → service.bindChannel(caseId, channelId)  [ring buffer initialized]

Engine calls provision(capabilities, context)
  → OpenClawWorkerProvisioner resolves agentId
  → registry.register(agentId, caseId, sessionKey)
  → service.bindAgent(agentId, caseId)

Engine calls postToChannel(channel, "engine", "Analyse this PR", COMMAND, correlationId, deadline)
  → OpenClawCaseChannelProvider calls MessageService.dispatch()
  → Qhorus writes message to ledger
  → ChannelContextWindowObserver.onMessage() → service.add() → ring buffer updated
  → ChannelGateway.fanOut() calls OpenClawChannelBackend.post()
  → COMMAND detected → registry.findAgentId(caseId) → agentId
  → hookClient.invoke(agentId, "Analyse this PR", model, timeout)

OpenClaw processes the prompt, runs code-review skill
Python SDK before_prompt_build fires → GET /channel-context/{agentId}?since=0
  → service.query(agentId, 0) → joins agentToCase → reads ring buffer → returns context window
  → context injected into system prompt

OpenClaw completes, POSTs result to webhook URL
  → POST /openclaw/delivery/channel/{channelId}
  → OpenClawDeliveryResource receives payload
  → MessageService.dispatch(DONE, agentOutput, to channel "case-{caseId}/work")
  → QhorusMessageSignalBridge bridges DONE to CaseHubRuntime.signal()
  → Engine resumes case
```

---

## 14. Out of Scope

- Speech act classification Phases 2–3 (openclaw#10)
- OpenClaw webhook payload format verification (openclaw#11)
- Reactive SPI variants (openclaw#12)
- Channel layout consolidation with Claudony (parent#93)
- `WorkerContextProvider` SPI — no OpenClaw-specific lineage context needed at this stage
- Heartbeat mode — not applicable for in-case direct call provisioning
- Multi-agent per case — `caseToAgent` is 1:1; multi-agent support requires a redesign of the registry (see §5 constraint)
- `caseChannels` map cleanup on case close — `unbindAgent()` removes the agent association but retains the `caseChannels` entry; TTL eviction handles ring buffer cleanup. A dedicated `closeCase(UUID caseId)` method is not implemented in MVP; add as openclaw#N if memory growth becomes observable.
- Startup recovery for context window — after restart, all in-memory state is empty; agents must be re-provisioned before context injection resumes
