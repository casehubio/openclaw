# Design: Layout extraction, gate crash recovery, channel context tenancyId-free query

**Branch:** `issue-32-layout-gate-tenancy`
**Covers:** #32, #34, #33
**Date:** 2026-06-14

---

## Issue #32 — `OpenClawNormativeLayout` extraction

### Problem

`OpenClawCaseChannelProvider` and `ReactiveOpenClawCaseChannelProvider` each define identical
`private record ChannelSpec(String description, String allowedTypes, String deniedTypes)` and
`private static final Map<String, ChannelSpec> LAYOUT` constants. Both carry a comment
pointing at `PLATFORM.md §Agent Communication Mesh` as the source of truth.

Two additional defects:
- `allowedTypes`/`deniedTypes` stored as `String`, parsed to `Set<MessageType>` at every
  `openChannel()` call site — duplicated parsing in both providers.
- No test-time guard detects divergence between the two `LAYOUT` maps.

### Design

New package-private `OpenClawNormativeLayout` in `io.casehub.openclaw.casehub`:

```java
final class OpenClawNormativeLayout {
    /**
     * @param allowedTypes message types permitted on this channel; null = unrestricted
     * @param deniedTypes  message types blocked on this channel; null = unrestricted
     */
    record ChannelSpec(
        String description,
        Set<MessageType> allowedTypes,
        Set<MessageType> deniedTypes
    ) {}

    static final Map<String, ChannelSpec> LAYOUT = Map.of(
        "work",     new ChannelSpec("Primary coordination — all obligation-carrying message types", null, null),
        "observe",  new ChannelSpec("Telemetry — EVENT only, no obligations created", Set.of(MessageType.EVENT), null),
        "oversight",new ChannelSpec("Human governance — agent actions pending human approval", null, Set.of(MessageType.EVENT))
    );

    private OpenClawNormativeLayout() {}
}
```

`ChannelSpec` holds `Set<MessageType>` directly. Both providers:
- Delete their local `ChannelSpec` record and `LAYOUT` field
- Replace all references with `OpenClawNormativeLayout.LAYOUT` and `OpenClawNormativeLayout.ChannelSpec`
- Delete all string-to-Set parsing code at call sites

### Test

`OpenClawNormativeLayoutTest`:
- Assert `LAYOUT.keySet()` equals `{"work", "observe", "oversight"}` — neither more nor fewer
- Assert `LAYOUT.get("observe").allowedTypes()` equals `Set.of(MessageType.EVENT)` and `deniedTypes()` is null
- Assert `LAYOUT.get("oversight").deniedTypes()` equals `Set.of(MessageType.EVENT)` and `allowedTypes()` is null
- Assert `LAYOUT.get("work").allowedTypes()` is null and `deniedTypes()` is null

These value assertions are the useful test: they verify the correct configuration is expressed, not just that the keySet exists. The "divergence between providers" is already compiler-enforced after extraction (both reference the same class).

---

## Issue #34 — Gate crash recovery for pre-#29 persisted gates

### Problem

`OversightGateService.fulfill()` reads `GateContext.tenancyId` from the serialized gate
COMMAND content. Gates opened before #29 have no `tenancyId` key → `parseGateContent()`
returns `Optional.of(GateContext(oci, wci, cmi, null))` → `tenancyId = null`.

`gateDispatcher.dispatch(..., null)` resolves tenancyId from `CurrentPrincipal`, which in
webhook context is `MockCurrentPrincipal` → `DEFAULT_TENANT_ID`. For non-default tenants,
all dispatches in `fulfill()` write to the wrong tenant's message store. The commitment
never closes for the affected tenant.

### Recovery design

`CrossTenantChannelStore` already exists in the Qhorus jar:
```java
public interface CrossTenantChannelStore {
    Optional<Channel> findById(UUID id);  // UUID-keyed, no tenant filter
    // ...
}
```

`Channel.tenancyId` is non-null (`@Column(nullable = false, updatable = false)`, defaults
to `DEFAULT_TENANT_ID`). `fulfill()` already has `UUID oversightChannelId = gateCmd.channelId`.

Add `@CrossTenant CrossTenantChannelStore crossTenantChannelStore` to `OversightGateService`
constructor (consistent with existing `@CrossTenant CrossTenantMessageStore`). In `fulfill()`,
after:
```java
String tenancyId = gateContext.map(GateContext::tenancyId).orElse(null);
```
insert:
```java
if (tenancyId == null) {
    tenancyId = crossTenantChannelStore.findById(oversightChannelId)
        .map(ch -> ch.tenancyId)
        .orElse(null);
    if (tenancyId != null)
        log.infof("fulfill(): recovered tenancyId from channel for pre-#29 gate %s", gateId);
    else
        log.warnf("fulfill(): cannot recover tenancyId for gate %s — oversight channel not found", gateId);
}
```

Dispatch proceeds with the recovered tenancyId. No separate upgrade note — the migration
path handles pre-#29 gates silently and automatically.

### Test changes (`OversightGateServiceTest`)

**Setup additions:**
```java
// New field:
CrossTenantChannelStore crossTenantChannelStore;

// In setup() — add these two lines:
crossTenantChannelStore = mock(CrossTenantChannelStore.class);
when(crossTenantChannelStore.findById(any())).thenReturn(Optional.empty()); // default: not found

// Constructor grows from 6 to 7 args — update the service instantiation:
service = new OversightGateService(channelService, messageService, commitmentStore,
        gateDispatcher, classifiers, crossTenantMessageStore, crossTenantChannelStore);
```

**New test 1:** Gate COMMAND content has `tenancyId` key absent → `crossTenantChannelStore.findById(oversightChannelId)` returns a channel with `tenancyId = "tenant-A"` → dispatcher receives `"tenant-A"` as the tenancyId arg. Verify `crossTenantChannelStore.findById(oversightChannelId)` was called exactly once.

**New test 2:** Gate COMMAND content has `tenancyId` key absent AND `crossTenantChannelStore.findById(oversightChannelId)` returns `Optional.empty()` → dispatcher called with `tenancyId = null` (existing fail-open behavior, now explicitly tested).

**No existing tests change.** All existing tests use `buildGateCommand(...)` which includes `tenancyId` in content → recovery path not triggered.

---

## Issue #33 — ChannelContextWindow tenancyId-free query

### Root cause

`ChannelContextWindowResource` reads `currentPrincipal.tenancyId()` to scope
`service.query(agentId, tenancyId, since)`. In plugin/SDK context, `CurrentPrincipal`
resolves to `MockCurrentPrincipal` → `DEFAULT_TENANT_ID`. For non-default tenants,
`agentToCase.get(new AgentKey(agentId, DEFAULT_TENANT_ID))` returns null →
`WindowContent.noAssociation()` → no context injected.

### Structural inconsistency in the current design

`ChannelContextWindowService` uses `ConcurrentHashMap<AgentKey, UUID> agentToCase` where
`AgentKey(agentId, tenancyId)` was introduced in #29 to anticipate concurrent same-agentId
workers across tenants. But `OpenClawAgentRegistry` uses `ConcurrentHashMap<String, UUID>
agentToCase` — plain agentId keys, last-write-wins per agentId across all tenants.

ARC42 §2 Constraints documents this explicitly: "No concurrent same-agentId workers:
`OpenClawAgentRegistry` is last-write-wins per agentId. A future engine-level fix
(workerId in WorkResult) unblocks concurrent workers."

The two maps are therefore structurally inconsistent: the registry says agentId is a global
key (no tenant scoping); the service says agentId requires tenant scoping. When concurrent
workers arrive, the fix is keyed on `workerId` (per ARC42) — at that point BOTH maps need to
rekey, not just the service. `AgentKey(agentId, tenancyId)` does not provide a useful
stepping stone toward that future.

### Design decision

**Remove `AgentKey` from `ChannelContextWindowService`**. This achieves consistency with
`OpenClawAgentRegistry`, eliminates the internal inconsistency, and provides a single
refactor point when `workerId` arrives.

Consequence: `tenancyId` is no longer part of any map key in the service. Drop it from
`bindAgent`/`unbindAgent` signatures too — it becomes vestigial.

Under the ARC42 §2 constraint (one agentId per deployment across all tenants), this is safe
today. When concurrent workers arrive, both maps (registry and service) are refactored
together to `workerId`-keyed.

### Production changes

**`ChannelContextWindowService` (`core/`):**
```java
// Replace:
private final ConcurrentHashMap<AgentKey, UUID> agentToCase = new ConcurrentHashMap<>();
// With:
private final ConcurrentHashMap<String, UUID> agentToCase = new ConcurrentHashMap<>();

// Signature changes:
public void bindAgent(String agentId, UUID caseId)      // drop tenancyId
public void unbindAgent(String agentId)                  // drop tenancyId, drop null guard
public WindowContent query(String agentId, long since)   // drop tenancyId — resolves from agentToCase.get(agentId)
```

`unbindAgent` loses its null-tenancyId guard — `ConcurrentHashMap.remove()` on an absent
key is already a no-op, so the guard was only needed because null could produce a wrong
`AgentKey`.

Delete `AgentKey.java`.

**`ChannelContextWindowResource` (`app/`):**
- Remove `@Inject CurrentPrincipal currentPrincipal`
- Change to `service.query(agentId, since)` (no tenancyId arg)
- Remove the Javadoc paragraph beginning "tenancyId is resolved via `CurrentPrincipal`..." — both its claims become false after the change

**Callers of `bindAgent`/`unbindAgent` (`casehub/`):**

`OpenClawWorkerProvisioner.provision()`:
- `service.bindAgent(agentId, tenancyId, caseId)` → `service.bindAgent(agentId, caseId)`

`OpenClawWorkerProvisioner.terminate()`:
- `service.unbindAgent(workerId, tenancyId)` → `service.unbindAgent(workerId)`
- Remove trailing comment `// null-safe: service logs warn and no-ops` — refers to the null-tenancyId guard that is being deleted

`OpenClawWorkerStatusListener.onWorkerCompleted()`:
- Line 52 comment: `// Capture caseId and tenancyId before deregistering` → `// Capture caseId before deregistering` (tenancyId no longer needed)
- Line 54: remove entirely — `String tenancyId = caseId != null ? registry.findTenancyId(caseId).orElse(null) : null;` is dead once `service.unbindAgent(workerId)` no longer takes tenancyId
- `service.unbindAgent(workerId, tenancyId)` → `service.unbindAgent(workerId)`

`ReactiveOpenClawWorkerProvisioner.provision()`:
- `service.bindAgent(agentId, tenancyId, caseId)` → `service.bindAgent(agentId, caseId)`
- `currentPrincipal.tenancyId()` call on line 61 **stays** — tenancyId is still read for `registry.register(agentId, tenancyId, caseId, sessionKey)`

`ReactiveOpenClawWorkerProvisioner.terminate()`:
- `service.unbindAgent(workerId, tenancyId)` → `service.unbindAgent(workerId)`
- Remove trailing comment `// null-safe: service logs warn and no-ops`

**Plugin/SDK:** no changes — both already pass only `agentId` to the REST endpoint.

### Test changes

**`ChannelContextWindowServiceTest` — tests removed (behaviors no longer exist):**

| Test | Why removed |
|------|-------------|
| `bindAgent_sameAgentId_differentTenants_independentWindows` | Requires two tenants with same agentId to coexist — impossible with single-key map |
| `query_wrongTenant_returnsNoAssociation` | Tests old 3-arg signature; wrong-tenant concept no longer applies |
| `unbindAgent_oneTenant_doesNotAffectOther` | Requires independent same-agentId bindings per tenant — impossible with single-key map |
| `unbindAgent_nullTenancyId_logsAndNoOps` | null-tenancyId guard removed; 1-arg unbindAgent cannot receive null tenancyId |

**`ChannelContextWindowServiceTest` — all remaining tests updated (signature change only):**
- `bindAgent("agent-1", "test-tenant", caseId)` → `bindAgent("agent-1", caseId)`
- `unbindAgent("agent-1", "test-tenant")` → `unbindAgent("agent-1")`
- `query("agent-1", "test-tenant", 0L)` → `query("agent-1", 0L)`

This affects approximately 15 tests. Behaviour preserved; only signature drops tenancyId.

**`CrossTenantContextIsolationTest` (`app/`) — entire test class removed.** Its purpose is
explicitly to test `AgentKey` isolation (see its class Javadoc). That behavior no longer
exists.

**`ChannelContextWindowResourceTest` — all 5 tests change:**

1. `get_knownAgent_returns200WithMessages` — remove `when(currentPrincipal.tenancyId())` stub; change service mock to `when(service.query("test-agent", 0L))`; remove `@InjectMock CurrentPrincipal`
2. `get_unknownAgent_returns200NotAssociated` — same principal removal; update service stub to 2-arg
3. `get_sinceParamPassedThrough` — same; update `verify(service).query("agent-1", 42L)`
4. `get_sinceDefaultsToZero` — same; update `verify(service).query("agent-1", 0L)`
5. `get_tenancyIdFromPrincipalPassedToService` — **removed entirely** (behavior gone — resource no longer reads principal)

**New resource test:** `get_queryCallsServiceWithAgentIdAndSince_noPrincipalInteraction` — verify `service.query("agent-1", 0L)` called, verify no interaction with any `CurrentPrincipal` mock.

**Caller tests (`casehub/`):**

`OpenClawWorkerProvisionerTest`:
- `verify(mockService).bindAgent("finance-agent", "test-tenant", caseId)` → `verify(mockService).bindAgent("finance-agent", caseId)`
- `verify(mockService).unbindAgent("finance-agent", "test-tenant")` → `verify(mockService).unbindAgent("finance-agent")`
- `verify(mockService).unbindAgent(eq("not-registered"), isNull())` → `verify(mockService).unbindAgent("not-registered")`
- `when(mockPrincipal.tenancyId()).thenReturn("test-tenant")` **remains** — provisioner still reads `currentPrincipal.tenancyId()` for `registry.register(agentId, tenancyId, ...)`. Same reasoning as the reactive variant.

`ReactiveOpenClawWorkerProvisionerTest`:
- `verify(mockService).bindAgent("code-review-agent", "test-tenant", caseId)` → `verify(mockService).bindAgent("code-review-agent", caseId)`
- `verify(mockService).unbindAgent("finance-agent", "test-tenant")` → `verify(mockService).unbindAgent("finance-agent")`
- `verify(mockService).unbindAgent(eq("not-registered"), isNull())` → `verify(mockService).unbindAgent("not-registered")`
- `when(mockPrincipal.tenancyId()).thenReturn("test-tenant")` **remains** — provisioner still reads `currentPrincipal.tenancyId()` for `registry.register(agentId, tenancyId, ...)`. Only the service call drops tenancyId; the registry call does not.

`OpenClawWorkerStatusListenerTest`:
- `onWorkerCompleted_callsUnbindAgentOnContextWindowService`: `verify(mockService).unbindAgent("agent-1", "test-tenant")` → `verify(mockService).unbindAgent("agent-1")`
- `onWorkerCompleted_readsTenancyIdBeforeDeregister` — **removed entirely**. The test's purpose was to verify tenancyId is read from the registry before `deregister()` clears it. With the tenancyId fetch removed from the production code, the test has no behavioral claim left.
- `onWorkerCompleted_unknownAgent_unbindAgentWithNullTenancyId`: `verify(mockService).unbindAgent(eq("unknown"), isNull())` → `verify(mockService).unbindAgent("unknown")`; rename to `onWorkerCompleted_unknownAgent_callsUnbindAgent`

### New service test

**`bindAgent_sameAgentId_secondCallOverwritesFirst`** — the primary behavioral consequence of removing `AgentKey`:

```java
@Test
void bindAgent_sameAgentId_secondCallOverwritesFirst() {
    UUID caseA = UUID.randomUUID(), caseB = UUID.randomUUID();
    UUID channelA = UUID.randomUUID(), channelB = UUID.randomUUID();
    service.bindAgent("bot", caseA);
    service.bindChannel(caseA, channelA);
    service.add(event(channelA, "case-a/work", MessageType.STATUS)); // seq=1

    service.bindAgent("bot", caseB);  // overwrites caseA binding
    service.bindChannel(caseB, channelB);
    service.add(event(channelB, "case-b/work", MessageType.COMMAND)); // seq=2

    WindowContent result = service.query("bot", 0L);
    assertThat(result.agentHasAssociation()).isTrue();
    // caseB's channel content — caseA's binding is gone
    assertThat(result.messages()).hasSize(1);
    assertThat(result.messages().get(0).messageType()).isEqualTo(MessageType.COMMAND);
}
```

This test documents the deliberate architectural choice and acts as a regression guard: if `AgentKey`-style isolation were restored, this test fails in the right direction.

The three tests labeled as "new" in the previous draft (`roundtrip`, `unbind-query`, `unknown-agent`) are signature updates of existing tests, not new behavior. Only the last-write-wins test above is genuinely new.

---

## Platform coherence

- All changes are fully scoped to `casehub-openclaw`. No cross-repo API changes.
- `#32`: `OpenClawNormativeLayout` is package-private — no risk of consumers depending on it.
  The Known Placement Violation for `CaseChannelLayout` (parent#93, Claudony) is unchanged.
- `#34`: `CrossTenantChannelStore` injection follows existing pattern in the same class.
- `#33`: `ChannelContextWindowService` and `AgentKey` change breaks call sites in `casehub/`
  module only. No peer-repo API surface changes. `ChannelContextWindowResource` in `app/`
  loses its `CurrentPrincipal` dependency — this endpoint is permanently unauthenticated,
  consistent with its role as an intelligence-layer (best-effort) query endpoint.
  The ARC42 §2 constraint is the load-bearing justification for single-key map safety.
