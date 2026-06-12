# Design: tenancyId propagation — openclaw#29

**Date:** 2026-06-12  
**Issue:** casehubio/openclaw#29  
**Branch:** issue-29-tenancyid-propagation  
**Approach:** Approach C — cross-repo `CrossTenantMessageStore` + explicit tenancyId threading

---

## Problem

Qhorus #260 added `tenancy_id` to Channel, Message, and Commitment entities. casehub-openclaw has
zero tenancy awareness: `OpenClawAgentRegistry`, `ChannelContextWindowService`, and
`OversightGateService` all operate without tenancyId. In a multi-tenant deployment:

- Two tenants using the same `agentId` corrupt each other's `ChannelContextWindowService` entry
- Delivery webhooks dispatch messages under DEFAULT_TENANT_ID regardless of the target channel's tenant
- Oversight gate fulfillment fails cross-tenant (all Qhorus reads are tenant-scoped, delivery webhooks have no casehub principal)

---

## Governing protocols

- **PP-20260520-439daf** — tenancy filtering always unconditional
- **PP-20260520-e6a5f0** — tenancyId binds in data access layer; call sites never read CurrentPrincipal for tenancy
- **PP-20260607-69eba2** — tenancyId server-side only; never in client-facing DTOs
- **PP-20260609-39c391** — ApplicationScoped services called from non-request contexts receive tenancyId as explicit parameter
- **PP-20260611-d4e5cf** — CDI event records crossing async boundaries carry tenancyId

---

## What is NOT changing

- `MessageReceivedEvent` — already carries `tenancyId` (added in qhorus#260); `ChannelContextWindowObserver` requires no changes
- `MessageDispatch.tenancyId` — already exists (null = auto-resolve from CurrentPrincipal); all request-context dispatch paths auto-resolve correctly and need no explicit setting
- `CaseChannelProvider` / `ReactiveCaseChannelProvider` — `openChannel()` and `postToChannel()` run in request context; `ChannelService.create()` and `MessageDispatch` auto-resolve tenancyId via CurrentPrincipal; no changes
- `ChannelContextWindowObserver`, `ChannelContextWindowResource` path for `add()` — channelId is globally unique; buffer lookup needs no tenancyId
- Delivery endpoint URL for channel delivery — tenancyId is recovered from `ChannelStore.find(UUID)` (cross-tenant UUID lookup); no path change needed

---

## Component changes

### 1. `OpenClawAgentRegistry` (`casehub/`)

Add fourth routing map: `caseToTenancy: ConcurrentHashMap<UUID, String>`.

```
register(agentId, tenancyId, caseId, sessionKey)   — stores tenancyId by caseId (UUID, globally unique)
findTenancyId(UUID caseId) → Optional<String>       — new; used by backend and status listener
deregister(String agentId)                          — also removes caseToTenancy entry via agentToCase lookup
```

CaseId is the correct key (globally unique UUID); agentId is not (same agentId can serve multiple tenants).

### 2. `ChannelContextWindowService` (`core/`)

Replace `agentToCase: ConcurrentHashMap<String, UUID>` with `agentToCase: ConcurrentHashMap<AgentKey, UUID>`.

`AgentKey` is a package-private record in `core/`:
```java
record AgentKey(String agentId, String tenancyId) {}
```
Records provide correct `equals`/`hashCode` — no manual implementation needed.

Three method signatures change:
```
bindAgent(String agentId, String tenancyId, UUID caseId)
unbindAgent(String agentId, String tenancyId)
query(String agentId, String tenancyId, long since)
```

`bindChannel`, `closeCase`, `add`, `evictExpired` are unchanged (keyed by channelId or caseId).

### 3. `OpenClawWorkerProvisioner` + `ReactiveOpenClawWorkerProvisioner` (`casehub/`)

Both inject `CurrentPrincipal`.

**Blocking provisioner** — `provision()`:
```java
String tenancyId = currentPrincipal.tenancyId();   // read on calling (request) thread
registry.register(agentId, tenancyId, caseId, sessionKey);
service.bindAgent(agentId, tenancyId, caseId);
```

`terminate(workerId)` recovers tenancyId without CurrentPrincipal:
```java
Optional<UUID> caseId = registry.findCaseId(workerId);
String tenancyId = caseId.flatMap(registry::findTenancyId).orElse(null);
registry.deregister(workerId);
if (tenancyId != null) service.unbindAgent(workerId, tenancyId);
```

**Reactive provisioner** — tenancyId captured before entering the Uni lambda (pre-subscription,
still on the requesting thread which has request scope):
```java
String tenancyId = currentPrincipal.tenancyId();   // captured here — request thread
return Uni.createFrom().item(() -> {
    // tenancyId is a captured local; no CurrentPrincipal access inside lambda
    registry.register(agentId, tenancyId, caseId, sessionKey);
    service.bindAgent(agentId, tenancyId, caseId);
    ...
});
```

`terminate()` is identical to the blocking variant (no request context).

### 4. `OpenClawWorkerStatusListener` (`casehub/`)

`onWorkerCompleted()` runs on the Vert.x event bus — no request scope. tenancyId is read from
the registry **before** `deregister()` removes the entry:
```java
UUID caseId = registry.findCaseId(workerId).orElse(null);
String tenancyId = caseId != null ? registry.findTenancyId(caseId).orElse(null) : null;
registry.deregister(workerId);
service.unbindAgent(workerId, tenancyId);   // null-safe: logs and skips if null
if (caseId != null) service.closeCase(caseId);
```

`unbindAgent()` must handle `tenancyId = null` gracefully (log warning, no-op).

### 5. `OpenClawChannelBackend` (`casehub/`)

`post()` constructs the webhook URL with tenancyId from the registry:
```java
String tenancyId = registry.findTenancyId(caseId).orElse("default");
String webhookUrl = config.delivery().baseUrl() + "/" + tenancyId + "/channel/" + channel.id();
```

`tenancyId` comes from the registry (stored at provision time in request context). No
`CurrentPrincipal` injection in the backend — PP-20260520-e6a5f0 compliant.

The `"default"` fallback is safe: a lookup miss means the agent was not provisioned (or registry
state is inconsistent), in which case the delivery endpoint would fail to find the channel for the
non-default tenant regardless.

The channel delivery endpoint path changes:
```
POST /openclaw/delivery/channel/{channelId}
→
POST /openclaw/delivery/{tenancyId}/channel/{channelId}
```
The resource extracts `@PathParam("tenancyId")` and passes it to `evaluate()`.

**Backward-incompatibility note:** sessions provisioned before this deployment have old webhook URLs
(without tenancyId). Those sessions will receive 404 on delivery. Acceptable at current maturity
(dev/test only). Add upgrade note to release.

### 6. `OversightGateService.evaluate()` (`casehub/`)

Signature: `evaluate(UUID workChannelId, String tenancyId, String agentId, String output)`

`MessageDispatch.builder()` gains `.tenancyId(tenancyId)`. No reads needed — evaluate is a
pure write path. tenancyId arrives from the channel delivery URL path parameter.

### 7. `GateContext` + `openGate()` (`casehub/`)

`GateContext` record gains `tenancyId`:
```java
private record GateContext(String originalCommitmentId, UUID workChannelId,
                           long commandMessageId, String tenancyId)
```

`serializeGateContent()` adds `tenancyId` to the Properties. `parseGateContent()` reads it; if
absent (pre-#29 gate), returns `Optional.empty()`. `fulfill()` logs a warning and falls back to
DEFAULT_TENANT_ID behaviour for such gates (see openclaw#34).

`openGate()` injects `CurrentPrincipal` (called from MCP tool endpoint — request context):
- Reads `String tenancyId = currentPrincipal.tenancyId()` at entry
- Passes to `GateContext` constructor
- Sets `.tenancyId(tenancyId)` on the oversight channel `MessageDispatch`

### 8. `OversightGateService.fulfill()` (`casehub/`)

`fulfill()` no longer bootstraps via `commitmentStore`. New flow using cross-tenant reads:

```
1. crossTenantMessageStore.findByCorrelationId(gateId.toString())
   → gate COMMAND message → oversightChannelId, commandMessageId
   → parseGateContent(message.content) → GateContext{tenancyId, workChannelId, originalCommitmentId}

2. channelStore.find(oversightChannelId)   // UUID lookup — cross-tenant
   → oversight Channel entity

3. channelStore.find(gateContext.workChannelId())   // UUID lookup — cross-tenant
   → work Channel entity

4. parseApproval(gateId, rawOutput)

5. gateDispatcher.dispatch(approved, oversightChannel.id, workChannel.id,
                           commandMessageId, gateId, rawOutput, gateContext, tenancyId)
```

`commitmentStore.findByCorrelationId()` is removed from `fulfill()`. The oversight channel ID
previously obtained from `commitment.channelId` is now recovered from the gate COMMAND message
directly — simpler and cross-tenant safe.

`OversightGateService` injects `@CrossTenant CrossTenantMessageStore` (new) alongside the
existing `ChannelStore` injection.

### 9. `OversightGateDispatcher` (`casehub/`)

`dispatch()` gains `String tenancyId` parameter. All `MessageDispatch.builder()` calls inside
add `.tenancyId(tenancyId)`. No other changes.

### 10. `ChannelContextWindowResource` (`app/`)

Injects `CurrentPrincipal`. Passes `currentPrincipal.tenancyId()` to `service.query()`:
```java
return service.query(agentId, currentPrincipal.tenancyId(), since);
```

No URL change. The Python SDK continues calling `GET /channel-context/{agentId}` unchanged.
In single-tenant deployments, `MockCurrentPrincipal` returns `DEFAULT_TENANT_ID` — identical to
current behaviour.

### 11. qhorus: `CrossTenantMessageStore` (new — separate PR on casehubio/qhorus)

```java
/** @CrossTenant CDI qualifier required. Refs casehubio/openclaw#29. */
public interface CrossTenantMessageStore {
    Optional<Message> findByCorrelationId(String correlationId);
}
```

Implementations:
- `JpaCrossTenantMessageStore` — `SELECT m FROM Message m WHERE m.correlationId = :correlationId`; no tenancy filter; returns first match
- `InMemoryCrossTenantMessageStore` in `testing/` — for `@QuarkusTest` use in casehub-openclaw

Follows `CrossTenantCommitmentStore` pattern verbatim. Filed as casehubio/qhorus issue (qhorus is a peer repo; this PR is opened from the qhorus session).

---

## Tests

### Unit tests (no Quarkus)

**`ChannelContextWindowServiceTest`** — new multi-tenant cases:
- Two agents with same `agentId`, different `tenancyId` → independent context windows
- `query("bot", "tenant-B", 0)` after binding "bot" to tenant-A returns `noAssociation()`
- `unbindAgent("bot", "tenant-A")` does not affect "bot" in tenant-B

**`OpenClawAgentRegistryTest`**:
- `findTenancyId(caseId)` returns tenancyId set at `register()` time
- `deregister(agentId)` removes tenancy entry; subsequent `findTenancyId(caseId)` returns empty

**`OversightGateServiceTest`**:
- `openGate()` serializes tenancyId into gate content (parse round-trip)
- `fulfill()` uses `CrossTenantMessageStore` stub; dispatches with tenancyId from GateContext
- Missing tenancyId in gate content → `parseGateContent()` returns empty → warning logged, no NPE

### `@QuarkusTest`

**`OpenClawWorkerProvisionerTest`** — `FixedCurrentPrincipal.setTenancyId("tenant-A")`:
- Registry receives tenancyId at provision
- Context window binds agent under `AgentKey("bot", "tenant-A")`

**`ChannelContextWindowResourceTest`**:
- `GET /channel-context/bot` with tenant-A principal → scoped to tenant-A window

**Delivery endpoint test** — `POST /openclaw/delivery/tenant-A/channel/{channelId}`:
- Routes to `evaluate()` with `tenancyId="tenant-A"`
- Dispatches `MessageDispatch` with `tenancyId="tenant-A"` (captured in `RecordingMessageService`)

**Cross-tenant isolation `@QuarkusTest`** (new):
- Provision "bot" for tenant-A → dispatch COMMAND on tenant-A channel
- `GET /channel-context/bot` with tenant-B principal → `noAssociation()`

---

## Deferred — issued

| Issue | Scope |
|-------|-------|
| casehubio/openclaw#33 | Python SDK tenancyId propagation (requires auth retrofit) |
| casehubio/openclaw#34 | Gate crash recovery for pre-#29 persisted gates |
| casehubio/engine#475 | `terminate()` SPI add `tenancyId` parameter |

---

## Cross-repo dependency

This PR depends on the qhorus `CrossTenantMessageStore` PR being merged and the
`casehub-qhorus` SNAPSHOT published before `casehub-openclaw` can compile. The qhorus PR must
be opened first; this PR gates on it.
