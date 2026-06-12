# Design: tenancyId propagation — openclaw#29

**Date:** 2026-06-12  
**Issue:** casehubio/openclaw#29  
**Branch:** issue-29-tenancyid-propagation  
**Revised after code review:** 2026-06-12

---

## Problem

Qhorus #260 added `tenancy_id` to Channel, Message, and Commitment entities and shipped a full
cross-tenant store infrastructure (`CrossTenantMessageStore`, `CrossTenantChannelStore`,
`CrossTenantCommitmentStore`, `@CrossTenant` CDI qualifier, JPA and in-memory implementations).
casehub-openclaw has zero tenancy awareness: `OpenClawAgentRegistry`,
`ChannelContextWindowService`, `OversightGateService`, and the delivery endpoints all operate
without tenancyId. In a multi-tenant deployment:

- Two tenants using the same `agentId` corrupt each other's `ChannelContextWindowService` entry
- `OpenClawDeliveryResource` calls `channelService.findById()` (tenant-scoped) — valid channels on
  non-default tenants return empty → 404 → OpenClaw retries → **double dispatch**, violating
  `openclaw-delivery-always-200` protocol
- Delivery webhooks dispatch messages under DEFAULT_TENANT_ID regardless of the target channel's tenant
- Oversight gate fulfillment fails cross-tenant (all Qhorus reads are tenant-scoped; delivery
  webhooks have no casehub principal)

---

## Governing protocols

- **PP-20260520-439daf** — tenancy filtering always unconditional
- **PP-20260520-e6a5f0** — tenancyId binds in data access layer; services never inject CurrentPrincipal for tenancy
- **PP-20260607-69eba2** — tenancyId server-side only; never in client-facing DTOs
- **PP-20260609-39c391** — ApplicationScoped services called from non-request contexts receive tenancyId as explicit parameter
- **PP-20260611-d4e5cf** — CDI event records crossing async boundaries carry tenancyId
- **openclaw-delivery-always-200** — delivery endpoints always return 200; never 404 or 5xx

---

## What is NOT changing

- `MessageReceivedEvent` — already carries `tenancyId` (qhorus#260); `ChannelContextWindowObserver` unchanged
- `MessageDispatch.tenancyId` — already exists (null = auto-resolve from CurrentPrincipal); all
  request-context dispatch paths (provisioner, postToChannel, openGate) auto-resolve correctly
  where an explicit set is not needed
- `CaseChannelProvider` / `ReactiveCaseChannelProvider` — run in request context; ChannelService
  and MessageDispatch auto-resolve tenancyId via CurrentPrincipal; unchanged
- `ChannelContextWindowService.bindChannel`, `closeCase`, `add`, `evictExpired` — keyed by
  channelId or caseId (globally unique UUIDs); unchanged
- Delivery endpoint URL for channel delivery — `POST /openclaw/delivery/channel/{channelId}`
  unchanged; tenancyId recovered at delivery time from channel entity via `CrossTenantChannelStore`
- **No qhorus PRs required** — `CrossTenantMessageStore`, `CrossTenantChannelStore`, their JPA
  implementations, and in-memory test variants all shipped in qhorus#260

---

## Component changes

### 1. `OpenClawAgentRegistry` (`casehub/`)

Add fourth routing map: `caseToTenancy: ConcurrentHashMap<UUID, String>`.

```
register(agentId, tenancyId, caseId, sessionKey)   — stores tenancyId by caseId (UUID, globally unique)
findTenancyId(UUID caseId) → Optional<String>       — new; used by status listener only
deregister(String agentId)                          — also removes caseToTenancy entry via agentToCase lookup
```

CaseId is the correct key (globally unique UUID); agentId is not (same agentId can serve multiple
tenants). `caseToTenancy` is populated at provision time (request context, tenancyId from
CurrentPrincipal) and consumed by `OpenClawWorkerStatusListener` (non-request context).
`OpenClawChannelBackend.post()` does NOT use this map — tenancyId is recovered at delivery time
from the channel entity (see §Delivery resource).

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
unbindAgent(String agentId, String tenancyId)       — null-safe: logs and no-ops if tenancyId null
query(String agentId, String tenancyId, long since)
```

`unbindAgent(agentId, null)` is a correct no-op: `AgentKey{agentId, null}` is never inserted
because `bindAgent()` only runs at provision time with a non-null tenancyId from `CurrentPrincipal`.
A null here means the agent was never provisioned (or deregistered before provision completed) —
no AgentKey to remove; log a warning and return.

### 3. `OpenClawWorkerProvisioner` + `ReactiveOpenClawWorkerProvisioner` (`casehub/`)

Both inject `CurrentPrincipal`.

**Blocking provisioner** — `provision()`:
```java
String tenancyId = currentPrincipal.tenancyId();   // read on calling (request) thread
registry.register(agentId, tenancyId, caseId, sessionKey);
service.bindAgent(agentId, tenancyId, caseId);
```

`terminate(workerId)` has no request context and no CurrentPrincipal; recovers tenancyId from
registry before deregistering (deregister removes the entry):
```java
Optional<UUID> caseId = registry.findCaseId(workerId);
String tenancyId = caseId.flatMap(registry::findTenancyId).orElse(null);
registry.deregister(workerId);
if (tenancyId != null) service.unbindAgent(workerId, tenancyId);
```

**Reactive provisioner** — tenancyId captured on the calling (request) thread before entering the
Uni lambda (the lambda executes on a worker thread with no request scope):
```java
String tenancyId = currentPrincipal.tenancyId();   // captured here — request thread
return Uni.createFrom().item(() -> {
    // tenancyId is a captured local — no CurrentPrincipal access inside lambda
    registry.register(agentId, tenancyId, caseId, sessionKey);
    service.bindAgent(agentId, tenancyId, caseId);
    ...
});
```

`terminate()` contains the same recovery logic as the blocking variant, wrapped in
`Uni.createFrom().item(Supplier)` — same logic, not the same code structure.

### 4. `OpenClawWorkerStatusListener` (`casehub/`)

`onWorkerCompleted()` runs on the Vert.x event bus — no request scope. tenancyId is read from
the registry **before** `deregister()` removes the entry:
```java
UUID caseId = registry.findCaseId(workerId).orElse(null);
String tenancyId = caseId != null ? registry.findTenancyId(caseId).orElse(null) : null;
registry.deregister(workerId);
service.unbindAgent(workerId, tenancyId);   // null-safe per §2
if (caseId != null) service.closeCase(caseId);
```

### 5. `OpenClawChannelBackend` (`casehub/`)

`post()` webhook URL is **unchanged**: `config.delivery().baseUrl() + "/channel/" + channel.id()`.
tenancyId is resolved at delivery time from the channel entity (see §Delivery resource). No
CurrentPrincipal injection, no registry tenancyId lookup.

### 6. `OpenClawDeliveryResource` (`app/`) — existing bug fixed

Currently injects `ChannelService` and calls `channelService.findById(channelId)` — tenant-scoped,
returns empty for valid channels on non-default tenants → 404 → OpenClaw retries → double dispatch.
This violates `openclaw-delivery-always-200`.

Replace with `@CrossTenant CrossTenantChannelStore` (already exists in qhorus#260):
```java
Optional<Channel> channel = crossTenantChannelStore.findById(channelId);
String tenancyId = channel.map(ch -> ch.tenancyId).orElse(null);
// tenancyId null = channel not found; evaluate() logs and skips dispatch
oversightGateService.evaluate(channelId, tenancyId, agentId, output);
return Response.ok().build();   // Always 200 — OpenClaw must not retry
```

Remove the 404 response path entirely. The existing `ChannelService` injection is removed.
If the channel is not found cross-tenant, `tenancyId` is null and `evaluate()` handles it
gracefully (§7). The always-200 invariant holds.

### 7. `OversightGateService.evaluate()` (`casehub/`)

Signature: `evaluate(UUID workChannelId, String tenancyId, String agentId, String output)`

tenancyId arrives from `OpenClawDeliveryResource` (from channel entity). If null (channel not
found), log a warning and return without dispatch:
```java
if (tenancyId == null) {
    log.warnf("evaluate(): null tenancyId for channelId=%s — channel not found; skipping dispatch", workChannelId);
    return;
}
```
Otherwise: `MessageDispatch.builder()...tenancyId(tenancyId)...build()`.

### 8. `GateContext` + `openGate()` (`casehub/`)

`GateContext` record gains `tenancyId`:
```java
private record GateContext(String originalCommitmentId, UUID workChannelId,
                           long commandMessageId, String tenancyId)
```

`serializeGateContent()` adds `tenancyId` to the Properties. `parseGateContent()` reads it;
returns `Optional.empty()` if absent (pre-#29 gate — see openclaw#34 for migration note).

**`openGate()` does NOT inject `CurrentPrincipal`** — PP-20260520-e6a5f0 forbids services from
reading CurrentPrincipal for tenancy. tenancyId is an explicit parameter:

```java
public GateDecision openGate(String agentId, String commitmentId, String outcome, String tenancyId)
```

The caller `CommitmentTools.done()` (an MCP tool running in request context) reads
`currentPrincipal.tenancyId()` and passes it down. `openGate()` stores tenancyId in GateContext,
and sets `.tenancyId(tenancyId)` on the oversight channel `MessageDispatch`.

### 9. `OversightGateService.fulfill()` (`casehub/`)

`fulfill()` no longer bootstraps via `commitmentStore` or loads channel entities. New flow:

```
1. crossTenantMessageStore.scan(
       MessageQuery.builder()
           .correlationId(gateId.toString())
           .messageType(MessageType.COMMAND)
           .build())
   → gate COMMAND message (or empty → log warn, return)
   → oversightChannelId (message.channelId), commandMessageId (message.id)
   → parseGateContent(message.content) → Optional<GateContext>

2. parseApproval(gateId, rawOutput)

3. gateDispatcher.dispatch(
       approved,
       oversightChannelId,
       commandMessageId, gateId, rawOutput,
       gateContext,          // Optional — absent for pre-#29 gates
       gateContext.map(GateContext::tenancyId).orElse(null))
```

No channel entity lookups. oversightChannelId comes from `message.channelId`; workChannelId and
tenancyId come from GateContext when present. `commitmentStore.findByCorrelationId()` is removed
from `fulfill()` entirely.

`OversightGateService` adds one new injection:
- `@CrossTenant CrossTenantMessageStore crossTenantMessageStore`

Removes from fulfill() path: no `ChannelStore`, no `CrossTenantChannelStore`, no `CommitmentStore`
lookup (commitmentStore remains for `openGate()` which still uses it in request context).

### 10. `OversightGateDispatcher` (`casehub/`)

`dispatch()` gains `String tenancyId` and removes the standalone `workChannelId` parameter
(redundant — workChannelId is in GateContext when present; absent path skips work channel):

```java
void dispatch(boolean approved,
              UUID oversightChannelId,
              long commandMessageId,
              UUID gateId,
              String rawOutput,
              Optional<GateContext> gateContext,
              String tenancyId)
```

All `MessageDispatch.builder()` calls add `.tenancyId(tenancyId)`. When `gateContext` is absent
(pre-#29 gate recovery), only the oversight channel RESPONSE/DECLINE is dispatched — the work
channel STATUS (previously dispatched via the standalone `workChannelId`) is skipped. This is
correct: without GateContext we have neither `workChannelId` nor `tenancyId` to scope it.

### 11. `ChannelContextWindowResource` (`app/`)

Injects `CurrentPrincipal`. Passes `currentPrincipal.tenancyId()` to `service.query()`:
```java
return service.query(agentId, currentPrincipal.tenancyId(), since);
```

No URL change. Both the Python SDK and TypeScript plugin (`plugin/src/channel-client.ts`)
continue calling `GET /channel-context/{agentId}` unchanged. In single-tenant deployments,
`MockCurrentPrincipal` returns `DEFAULT_TENANT_ID` — identical to current behaviour.
Multi-tenant support for both plugins deferred to openclaw#33 (requires auth retrofit).

---

## Tests

### Unit tests (no Quarkus)

**`ChannelContextWindowServiceTest`** — new multi-tenant cases:
- Two agents with same `agentId`, different `tenancyId` → independent context windows
- `query("bot", "tenant-B", 0)` after binding "bot" to tenant-A returns `noAssociation()`
- `unbindAgent("bot", "tenant-A")` does not affect "bot" in tenant-B
- `unbindAgent("bot", null)` logs warning and no-ops safely

**`OpenClawAgentRegistryTest`**:
- `findTenancyId(caseId)` returns tenancyId set at `register()` time
- `deregister(agentId)` removes tenancy entry; subsequent `findTenancyId(caseId)` returns empty

**`OversightGateServiceTest`**:
- `openGate(agentId, commitmentId, outcome, "tenant-A")` serializes tenancyId into gate content
  (parse round-trip)
- `fulfill()` uses stub `CrossTenantMessageStore`; dispatches with tenancyId from GateContext
- Gate COMMAND not found → log warning, no dispatch, no exception
- Missing tenancyId in gate content → `parseGateContent()` returns empty → gateContext absent →
  dispatcher skips work channel, dispatches only to oversight channel

**`OversightGateDispatcherTest`**:
- `dispatch(..., gateContext.present(), tenancyId)` → all MessageDispatch calls carry tenancyId
- `dispatch(..., gateContext.absent(), tenancyId)` → only oversight channel dispatch; work channel skipped

### `@QuarkusTest`

**`OpenClawWorkerProvisionerTest`** — `FixedCurrentPrincipal.setTenancyId("tenant-A")`:
- Registry receives tenancyId at provision
- Context window binds agent under `AgentKey("bot", "tenant-A")`

**`OpenClawDeliveryResourceTest`**:
- Valid channel on non-default tenant → 200 (not 404) and correct tenancyId passed to evaluate
- Unknown channel → 200, tenancyId null, evaluate() logs and skips dispatch

**`ChannelContextWindowResourceTest`**:
- `GET /channel-context/bot` with tenant-A principal → scoped to tenant-A window

**Cross-tenant isolation `@QuarkusTest`** (new):
- Provision "bot" for tenant-A → bind channel
- `GET /channel-context/bot` with tenant-B principal → `noAssociation()`

---

## Deferred — issued

| Issue | Scope |
|-------|-------|
| casehubio/openclaw#33 | TypeScript plugin + Python SDK tenancyId propagation (both L4 and L5; requires auth retrofit) |
| casehubio/openclaw#34 | Gate crash recovery / upgrade note for pre-#29 persisted gates (absent GateContext.tenancyId) |
| casehubio/engine#475 | `terminate()` SPI add `tenancyId` parameter |

---

## No cross-repo PRs required

All required qhorus infrastructure shipped in qhorus#260:
- `CrossTenantMessageStore` + `JpaCrossTenantMessageStore` + `InMemoryCrossTenantMessageStore`
- `CrossTenantChannelStore` + `JpaCrossTenantChannelStore` + `InMemoryCrossTenantChannelStore`
- `CrossTenantCommitmentStore` (not used in this PR but available)
- `@CrossTenant` CDI qualifier
- `MessageQuery` with `.correlationId()` and `.messageType()` support
