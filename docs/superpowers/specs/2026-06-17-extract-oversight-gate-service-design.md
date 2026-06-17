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

Additionally, the current `OversightGateService.classifyMostRestrictive()` is a partial
re-implementation of `ChainedReactiveActionRiskClassifier` (engine runtime) that silently drops
all `ReactiveActionRiskClassifier` beans registered in the deployment. This is corrected as part
of this extraction.

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

### `GateDecision`

New type in `io.casehub.api.spi`:

```java
public sealed interface GateDecision permits GateDecision.Autonomous, GateDecision.GatePending {
    record Autonomous() implements GateDecision {}
    record GatePending(UUID gateId, String reason) implements GateDecision {}
}
```

Pure Java, no deps. Moved from `io.casehub.openclaw.casehub.GateDecision`.

### `OversightGateService` (blocking)

New type in `io.casehub.api.spi`:

```java
public interface OversightGateService {
    /**
     * Evaluates the proposed action for risk and returns a gate decision.
     * Returns {@link GateDecision.Autonomous} when the action may proceed without human review.
     * Returns {@link GateDecision.GatePending} when the action requires human approval before
     * the commitment can be fulfilled. Implementations must fail-open on infrastructure errors.
     */
    GateDecision openGate(String agentId, String commitmentId, String outcome, String tenancyId);

    /**
     * Processes a human response to a pending oversight gate identified by {@code gateId}.
     * The raw output is interpreted by the implementation to determine approval or rejection.
     * Implementations must fail-open on errors — an unprocessable response must not block the case.
     */
    void fulfill(UUID gateId, String rawOutput);
}
```

`evaluate()` (OpenClaw webhook archiving) is NOT in the SPI — it is OpenClaw-specific and has no
equivalent in other harnesses.

### `ReactiveOversightGateService` (Mutiny)

New type in `io.casehub.api.spi`:

```java
public interface ReactiveOversightGateService {
    Uni<GateDecision> openGate(String agentId, String commitmentId, String outcome, String tenancyId);
    Uni<Void> fulfill(UUID gateId, String rawOutput);
}
```

Follows reactive parity established by `ReactiveWorkerProvisioner`, `ReactiveActionRiskClassifier`.
No identified consumer in the current openclaw deployment — both `CommitmentTools` (MCP, blocking)
and `OpenClawOversightDeliveryResource` (JAX-RS, blocking) call the blocking interface. Parity is
one reason to add it; the concrete technical justification is stronger: `OpenClawOversightGateService.openGate()`
calls `reactiveClassifier.classify(action).await().indefinitely()`, making it unsafe to call from a
Vert.x IO thread (deadlock or `BlockingNotAllowedException`). Reactive callers — including any
future engine consumer — MUST use `ReactiveOversightGateService.openGate()`. The interface exists
to enforce this boundary explicitly rather than letting callers discover it at runtime.

No qualifier annotation — `OversightGateService` is a singleton SPI, not a `@RiskClassifier`-style
multi-implementation chain.

### `ChainedReactiveActionRiskClassifier` — move to engine-api

**This is a new requirement for the engine session.**

`ChainedReactiveActionRiskClassifier` currently lives in `casehub-engine` runtime
(`io.casehub.engine.internal.worker`). It must be moved to `casehub-engine-api` so harnesses that
depend only on engine-api (not engine runtime) can inject `ReactiveActionRiskClassifier`.

Rationale: casehub-openclaw-casehub depends on `casehub-engine-api` only, deliberately avoiding
engine runtime because engine beans require `WorkerExecutionManager`, `JobScheduler`, and
`RoutingCursorStore` SPIs that openclaw does not provide. Without this move, injecting
`ReactiveActionRiskClassifier` in openclaw would cause an `UnsatisfiedResolutionException` — there
is no `@DefaultBean NoOpReactiveActionRiskClassifier` anywhere.

`ChainedReactiveActionRiskClassifier` has no engine-runtime-specific deps:
- `@ApplicationScoped`, `@Inject`, `Instance<T>` — CDI (jakarta.enterprise.cdi-api, Tier-1-acceptable per PLATFORM.md)
- `Uni`, `Infrastructure` — Mutiny (already in engine-api)
- `Logger` — JBoss logging (transitive via Mutiny)

**Package:** `io.casehub.api.classification`. Not `io.casehub.api.spi` — that package holds
interfaces and annotations only; placing a concrete `@ApplicationScoped` bean there would
immediately break the structural convention. The move to engine-api is a deliberate expansion of
engine-api's role: from "pure interfaces and annotations" to "interfaces, annotations, and
canonical default implementations that harnesses cannot access without full engine runtime." This
is an intentional design decision, not accidental — state it in the engine session commit message.
The `classification` package name scopes the precedent explicitly; it is not intended as a general
`impl` home for arbitrary engine-api beans.

**Consequence:** the engine session must publish a new engine-api snapshot before openclaw
implements the changes in this spec.

---

## engine Runtime Additions

Two `@DefaultBean @ApplicationScoped` implementations in `io.casehub.engine.internal.worker`
(same package as `NoOpWorkerProvisioner`):

**`NoOpOversightGateService`**
- `openGate()` → returns `new GateDecision.Autonomous()`
- `fulfill()` → no-op
- Observes `StartupEvent` and logs a single `WARN`: *"OversightGateService: no implementation
  configured — all actions proceed autonomously. Deploy an @ApplicationScoped OversightGateService
  implementation to enable oversight gating."* The observer only fires when the NoOp is the active
  bean, making misconfigured deployments observable without per-call noise. Fail-open semantics:
  a deployment without a harness gate service works correctly — oversight is simply absent, not
  broken.

**`NoOpReactiveOversightGateService`**
- `openGate()` → `Uni.createFrom().item(new GateDecision.Autonomous())`
- `fulfill()` → `Uni.createFrom().voidItem()`
- Same startup WARN via `@Observes StartupEvent`.

---

## casehub-openclaw Changes

### New classes

**`OpenClawOversightGateService implements OversightGateService`** — rename of the existing
`OversightGateService`. The constructor changes to inject `ReactiveActionRiskClassifier` (resolved
by CDI to the `ChainedReactiveActionRiskClassifier` moved to engine-api) instead of
`@RiskClassifier Instance<ActionRiskClassifier>`.

**`openGate()` implementation change (critical):**
The three private methods `classifyMostRestrictive()`, `mostRestrictive()`, and `narrower()` are
**deleted**. Classification delegates to the injected `ReactiveActionRiskClassifier`:

```java
RiskDecision decision = reactiveClassifier.classify(action).await().indefinitely();
```

`await().indefinitely()` is valid — the MCP tool handler (`CommitmentTools`) runs in a `@Blocking`
Quarkus worker thread. This single line replaces all three private methods and correctly handles
both blocking and reactive domain classifiers, including fail-safe semantics already implemented in
`ChainedReactiveActionRiskClassifier`.

**Thread constraint:** `await().indefinitely()` makes `OpenClawOversightGateService.openGate()`
unsafe on Vert.x IO threads. Any caller on an IO thread (reactive JAX-RS, Vert.x handler) must use
`ReactiveOversightGateService.openGate()` instead. This is the concrete technical justification for
the reactive interface — not only parity, but a hard thread-safety boundary that future engine
consumers must respect.

**`evaluate()` stays** as a method on `OpenClawOversightGateService` — not in the interface.

**`GATE_SENDER`** constant remains on `OpenClawOversightGateService`. `OversightGateDispatcher`
updated to reference `OpenClawOversightGateService.GATE_SENDER`.

**`OversightGateDispatcher`** and **`GateContext`** remain package-private in openclaw —
implementation details, not SPI surface.

**`ReactiveOpenClawOversightGateService implements ReactiveOversightGateService`** — new class,
using the **thin delegate pattern**: injects `OpenClawOversightGateService` (not the interface —
the concrete class, which carries `evaluate()`) and wraps each call:
- `openGate()` → `Uni.createFrom().item(() -> delegate.openGate(...))` — offloads to worker pool
- `fulfill()` → `Uni.createFrom().item(() -> { delegate.fulfill(...); return null; }).replaceWithVoid()`

This is NOT the split-class pattern of `ReactiveOpenClawWorkerProvisioner` (which injects its own
deps and re-implements logic independently). A full reactive re-implementation here would require
reactive Qhorus services (`ReactiveMessageService`, `ReactiveChannelService`) which are
`@IfBuildProperty(name = "casehub.qhorus.reactive.enabled", stringValue = "true")-gated`, coupling
the reactive gate service to the reactive Qhorus deployment property. The thin delegate avoids that
coupling: `ReactiveOpenClawOversightGateService` carries no reactive Qhorus deps and requires no
`@IfBuildProperty` gate — it is always safe to activate.

Has no current injection caller but required for the thread-safety boundary described above.

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

`CaseChannelNames` is deleted. `CaseChannelNamesTest` is deleted. Production callers migrate:

| Old | Replacement | Production callers |
|---|---|---|
| `CaseChannelNames.extractCaseId(name)` | `CaseChannel.parseCaseId(name)` | `OversightGateService`, `OpenClawChannelBackend` |
| `CaseChannelNames.oversightChannelName(caseId)` | `CaseChannel.oversightChannelName(caseId)` | `OversightGateService` |
| `CaseChannelNames.workChannelName(caseId)` | *(no replacement needed)* | none — dead code, test-only callers |

`workChannelName()` has no production callers (confirmed with `ide_find_references` — only
`CaseChannelNamesTest` round-trip test uses it). Deleted without substitution.

---

## Testing

### engine (engine session's responsibility)

- Unit tests for `GateDecision` record construction and sealed interface exhaustiveness
- Unit test for `NoOpOversightGateService`: `openGate()` returns `Autonomous`, `fulfill()` is silent
- Unit test for `NoOpReactiveOversightGateService`: same assertions via `.await().indefinitely()`
- Integration test confirming `ChainedReactiveActionRiskClassifier` satisfies `ReactiveActionRiskClassifier`
  injection in openclaw-equivalent CDI context (no engine-runtime-specific beans present)

### casehub-openclaw

- `OversightGateServiceTest` → `OpenClawOversightGateServiceTest` (rename + import updates)
- Constructor update: `@RiskClassifier Instance<ActionRiskClassifier>` replaced with
  `ReactiveActionRiskClassifier`; mock type in test setup changes accordingly
- Existing test cases for `classifyMostRestrictive()` behaviour (single classifier, multiple,
  fail-safe, most-restrictive selection) — deleted, as this logic now lives in
  `ChainedReactiveActionRiskClassifier` (tested in engine)
- Remaining test cases retained intact: `evaluate()`, `fulfill()`, channel/commitment lookup
  fail-open paths, tenancyId recovery
- `CommitmentToolsTest`, `OpenClawOversightDeliveryResourceTest`: mock type changes from class to
  interface for `OversightGateService` fields — no behavioural difference with Mockito
- `OpenClawDeliveryResourceTest`: injects `OpenClawOversightGateService` (concrete) for
  `evaluate()` tests — no change
- `OversightGateDispatcherTest`: one reference `OversightGateService.GATE_SENDER` →
  `OpenClawOversightGateService.GATE_SENDER`
- `OversightGateDispatcherCdiTest`: unaffected

---

## Cross-Repo Sequencing

1. **engine session** (blocking dependency for openclaw — both engine-api and runtime changes done together)
   - Move `ChainedReactiveActionRiskClassifier` from engine runtime to `casehub-engine-api`
     (`io.casehub.api.classification` package)
   - Add `GateDecision`, `OversightGateService`, `ReactiveOversightGateService` to engine-api
     (`io.casehub.api.spi`)
   - Add `NoOpOversightGateService @DefaultBean` (with startup WARN) to engine runtime
   - Add `NoOpReactiveOversightGateService @DefaultBean` (with startup WARN) to engine runtime
   - Publish new `casehub-engine-api` snapshot

2. **openclaw session** (blocked on step 1)
   - Update pom to consume new engine-api snapshot
   - Rename `OversightGateService` → `OpenClawOversightGateService implements OversightGateService`
   - Delete `classifyMostRestrictive()`, `mostRestrictive()`, `narrower()`; inject
     `ReactiveActionRiskClassifier`; call `.classify(action).await().indefinitely()`
   - Add `ReactiveOpenClawOversightGateService implements ReactiveOversightGateService`
   - Remove `CaseChannelNames`; update all callers
   - Update all callers and tests per tables above
   - Verify full build green

---

## PLATFORM.md Update (casehubio/parent — file issue, never commit directly)

- Remove `OversightGateService` from Known Placement Violations table
- Update Capability Ownership table:
  > **Oversight gate lifecycle** | `casehub-engine-api` (interface) / `casehub-openclaw` (impl) |
  > `OversightGateService`, `ReactiveOversightGateService`, `GateDecision`
- Update cross-repo dependency map: `casehub-engine-api` → `casehub-openclaw casehub` now includes
  `OversightGateService`, `ReactiveOversightGateService`, `GateDecision`
