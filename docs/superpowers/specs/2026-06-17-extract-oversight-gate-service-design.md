# Extract OversightGateService to casehub-engine-api — Design Spec

**Issue:** casehubio/openclaw#31
**Date:** 2026-06-17
**Status:** Approved — pending engine implementation

---

## Problem

`OversightGateService` is listed in `PLATFORM.md` as a Known Placement Violation:

> **Oversight gate lifecycle** | Current home: `casehub-openclaw` | **Intended home: `casehub-engine-api`**

The oversight gate — classify a proposed action for risk, route to human oversight if required,
await fulfillment — is a platform-wide concern. Any agent harness needs the same gate. Keeping
the SPI in casehub-openclaw forces each future harness to re-implement it or depend on an
integration repo.

The `ActionRiskClassifier`, `PlannedAction`, and `RiskDecision` types are already in
`casehub-engine-api`. The classification surface is present; the gate service surface is not.

---

## Decision

**Option 1: SPI interface + `GateDecision` in engine-api, implementation stays in openclaw.**

`casehub-engine-api` defines the contract. casehub-openclaw provides the Qhorus-backed
implementation. Other harnesses (claudony, future) can provide their own implementations.

Rejected alternatives:
- **Extract to `casehub-engine-oversight` module** — would pull `casehub-qhorus` runtime into the
  engine module graph, coupling foundation to integration. Adds a module with no architectural
  benefit.
- **Move `GateContext` to engine-api** — `GateContext` is a serialization detail (Properties-format
  content field) specific to the current Qhorus-backed implementation. It is not SPI surface.

---

## engine-api Additions

Three new types in `io.casehub.api.spi` (same package as `ActionRiskClassifier`, `RiskDecision`):

### `GateDecision`

```java
public sealed interface GateDecision permits GateDecision.Autonomous, GateDecision.GatePending {
    record Autonomous() implements GateDecision {}
    record GatePending(UUID gateId, String reason) implements GateDecision {}
}
```

Pure Java, no deps. Moved from `io.casehub.openclaw.casehub.GateDecision`.

### `OversightGateService` (blocking)

```java
public interface OversightGateService {
    GateDecision openGate(String agentId, String commitmentId, String outcome, String tenancyId);
    void fulfill(UUID gateId, String rawOutput);
}
```

`openGate()` — classifies the proposed action via `@RiskClassifier` beans. If `GateRequired`,
dispatches a COMMAND to the oversight channel and returns `GatePending`. If `Autonomous` (or no
classifiers registered), returns `Autonomous`. Fail-open on infrastructure errors.

`fulfill()` — processes the oversight agent's response. Dispatches RESPONSE (approve) or DECLINE
(reject) to the oversight channel, and DONE or DECLINE to the original work channel commitment.

`evaluate()` (OpenClaw webhook archiving) is NOT in the SPI — it is OpenClaw-specific and has no
equivalent in other harnesses.

### `ReactiveOversightGateService` (Mutiny)

```java
public interface ReactiveOversightGateService {
    Uni<GateDecision> openGate(String agentId, String commitmentId, String outcome, String tenancyId);
    Uni<Void> fulfill(UUID gateId, String rawOutput);
}
```

Follows reactive parity pattern established by `ReactiveWorkerProvisioner`,
`ReactiveActionRiskClassifier`.

No new qualifier annotation — `OversightGateService` is a singleton SPI, not a multi-implementation
chain. `@DefaultBean` CDI discovery is sufficient.

---

## engine Runtime Additions

Two `@DefaultBean @ApplicationScoped` implementations in
`io.casehub.engine.internal.worker` (same package as `NoOpWorkerProvisioner`):

**`NoOpOversightGateService`**
- `openGate()` → returns `new GateDecision.Autonomous()`
- `fulfill()` → no-op

**`NoOpReactiveOversightGateService`**
- `openGate()` → `Uni.createFrom().item(new GateDecision.Autonomous())`
- `fulfill()` → `Uni.createFrom().voidItem()`

Neither throws. Neither logs. Fail-open semantics: a deployment without a harness gate
implementation proceeds autonomously rather than failing. This is correct for operational SPI
semantics.

---

## casehub-openclaw Changes

### Rename and implement

`OversightGateService` → `OpenClawOversightGateService implements OversightGateService`.

- `openGate()` and `fulfill()` implement the SPI contract. Behaviour unchanged.
- `evaluate()` stays as a method on `OpenClawOversightGateService` — not in the interface.
- `GATE_SENDER` constant remains on `OpenClawOversightGateService`.
- `OversightGateDispatcher` updated to reference `OpenClawOversightGateService.GATE_SENDER`.
- `GateContext` and `OversightGateDispatcher` remain package-private in openclaw — implementation
  details, not SPI surface.

### Caller injection changes

| Caller | Injects | Why |
|---|---|---|
| `CommitmentTools` | `OversightGateService` (interface) | calls `openGate()` only |
| `OpenClawOversightDeliveryResource` | `OversightGateService` (interface) | calls `fulfill()` only |
| `OpenClawDeliveryResource` | `OpenClawOversightGateService` (concrete) | calls `evaluate()`, which is not on the SPI |

Injecting the concrete class in `OpenClawDeliveryResource` is intentional — it is
OpenClaw-specific infrastructure with no platform-level abstraction.

### GateDecision import update

All references: `io.casehub.openclaw.casehub.GateDecision` → `io.casehub.api.spi.GateDecision`.
`GateDecision.java` deleted from openclaw once engine-api publishes.

### CaseChannelNames removal

`CaseChannelNames` is deleted. It duplicates `CaseChannel` static methods already in engine-api:

| Old | Replacement |
|---|---|
| `CaseChannelNames.extractCaseId(name)` | `CaseChannel.parseCaseId(name)` |
| `CaseChannelNames.oversightChannelName(caseId)` | `CaseChannel.oversightChannelName(caseId)` |
| `CaseChannelNames.workChannelName(caseId)` | `CaseChannel.channelName(caseId, "work")` |

`CaseChannelNamesTest` deleted — equivalent coverage exists in engine-api's `CaseChannel` tests.

---

## Testing

### engine (engine session's responsibility)

- Unit tests for `GateDecision` record construction and sealed interface exhaustiveness
- Unit tests for `NoOpOversightGateService`: `openGate()` returns `Autonomous`, `fulfill()` is silent
- Unit tests for `NoOpReactiveOversightGateService`: same assertions via `.await().indefinitely()`

### casehub-openclaw

- `OversightGateServiceTest` → `OpenClawOversightGateServiceTest` (rename + import updates)
- All existing test cases retained intact — behaviour is unchanged
- `CommitmentToolsTest`, `OpenClawDeliveryResourceTest`: mock type changes from class to interface
  for `OversightGateService` fields — no behavioural difference with Mockito
- `OpenClawDeliveryResourceTest`: injects `OpenClawOversightGateService` (concrete) for
  `evaluate()` tests — no change
- `OversightGateDispatcherTest`: one reference to `OversightGateService.GATE_SENDER` (line 123)
  → `OpenClawOversightGateService.GATE_SENDER`; otherwise unaffected
- `OversightGateDispatcherCdiTest`: unaffected

No new test cases required — behaviour does not change, only the type boundary moves.

---

## Cross-Repo Sequencing

1. **engine session** — adds `GateDecision`, `OversightGateService`, `ReactiveOversightGateService`
   to `casehub-engine/api/`; adds `NoOp*` implementations to `casehub-engine/runtime/`; publishes
   `casehub-engine-api` snapshot
2. **openclaw session** — updates `casehub-openclaw` pom to consume new engine-api snapshot;
   renames `OversightGateService` → `OpenClawOversightGateService implements OversightGateService`;
   removes `CaseChannelNames`; updates all callers and tests; verifies full build green

The openclaw changes are blocked on engine publishing the new snapshot. No partial implementation
should be merged until the engine-api snapshot is available.

---

## PLATFORM.md Update

After both sessions complete, `PLATFORM.md` Known Placement Violations table entry for
`OversightGateService` is removed. Capability Ownership table entry updated:

> **Oversight gate lifecycle** | `casehub-engine-api` (interface) / `casehub-openclaw` (impl) |
> `OversightGateService`, `ReactiveOversightGateService`, `GateDecision`

This update goes to `casehubio/parent` (file a GitHub issue from the openclaw session —
never commit to parent directly).
