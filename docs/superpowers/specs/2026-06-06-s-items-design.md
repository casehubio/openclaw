# S-Items Design — openclaw#20, #22, #25

Branch: `issue-20-channel-crash-recovery`  
Date: 2026-06-06  
Revised: 2026-06-06 (post-review ×3)

---

## openclaw#25 — Oversight channel `deniedTypes`

### Problem

`OpenClawCaseChannelProvider` creates the oversight channel with `allowedTypes = null`
(unrestricted), pending claudony#142. That issue is now closed. PLATFORM.md specifies
the oversight channel uses `deniedTypes = EVENT` — all obligation-carrying types allowed,
no telemetry on the governance channel.

### Design

Introduce a `private record ChannelSpec(String description, String allowedTypes, String deniedTypes)`
nested inside `OpenClawCaseChannelProvider`. Replace `Map<String, String[]> LAYOUT` with
`Map<String, ChannelSpec> LAYOUT`. Named accessors replace positional index access.

```java
private record ChannelSpec(String description, String allowedTypes, String deniedTypes) {}

private static final Map<String, ChannelSpec> LAYOUT = Map.of(
    "work",     new ChannelSpec("Primary coordination — all obligation-carrying message types", null, null),
    "observe",  new ChannelSpec("Telemetry — EVENT only, no obligations created", "EVENT", null),
    "oversight",new ChannelSpec("Human governance — agent actions pending human approval", null, "EVENT")
);
```

`openChannel()` reads `spec.allowedTypes()` and `spec.deniedTypes()` and passes both to
the 10-param `ChannelService.create()` overload (confirmed in ChannelService.java:72-80;
the compact constructor of `ChannelCreateRequest` validates non-overlap and type names).
The stale comment referencing claudony#142 is removed.

**Idempotency gap:** `openChannel()` calls `channelService.findByName()` first and returns
the existing channel if found, skipping `create()`. Pre-existing oversight channels retain
`deniedTypes = null`. Acceptable: codebase is greenfield, no production channels exist,
and the oversight channel is per-case so every new case after the fix gets a
correctly-configured channel.

### Tests

Update `OpenClawCaseChannelProviderTest` — **critical**: existing `create()` stubs use 9
`any()` matchers (9-param overload). After the fix calls the 10-param overload, Mockito
will not match and return null, causing a NullPointerException that looks unrelated to the
change. All three `channelService.create()` stub calls must be updated to 10 matchers.
Assertions:
- oversight channel: `deniedTypes = "EVENT"`, `allowedTypes = null`
- work channel: both null
- observe channel: `allowedTypes = "EVENT"`, `deniedTypes = null`

---

## openclaw#20 — Crash-safe `channelId` recovery

### Problem

`CommitmentTools` keeps a `ConcurrentHashMap<String, UUID> channelMap`
(correlationId → channelId) for channel-backed commitments. On Quarkus restart this map
is empty, causing COMMITMENT_NOT_FOUND errors for tools that need the channelId even
though the Qhorus `Commitment` entity in the database carries `channelId`.

### Approach: drop the map

The map is a premature optimisation. MCP tool calls occur at AI agent turn latency
(hundreds of milliseconds to seconds) — one extra DB read per call is invisible. The map's
cost — crash-recovery spec work, pre-existing nullable constraint complications, additional
state in every tool method — is not worth the benefit.

**Drop `channelMap` entirely.** Read `channelId` from the persisted `Commitment` entity
on every tool call that needs it.

### Self-commit channelId — corrected reasoning

`selfCommit()` calls:
```java
commitmentService.open(
    UUID.randomUUID(),   // arg 1 = entity PK (not channelId)
    correlationId,       // arg 2
    null,                // arg 3 = channelId — null for self-commits
    MessageType.COMMAND, agentId, agentId, deadline);
```

The random UUID is the entity PK; channelId (arg 3) is `null`.

**Pre-existing nullable constraint note:** `Commitment.channelId` has
`@Column(nullable = false)`. Passing null for channelId in self-commits would throw a
NOT NULL constraint violation in production — self-commits likely never persist there.
`InMemoryMessageStore` does not enforce JPA constraints, so self-commit tests pass.
This is a pre-existing bug acknowledged but not fixed here. For this fix,
`resolveChannelId()` returning `Optional.empty()` for self-commits is correct regardless
of path: via the `null` filter (InMemory) or via `findByCorrelationId()` returning empty
(production, where the commitment never persisted).

### Design

**`resolveChannelId(String correlationId)`** — replaces all `channelMap.get()` calls:

```java
private Optional<UUID> resolveChannelId(String correlationId) {
    return commitmentStore.findByCorrelationId(correlationId)
        .filter(c -> !c.state.isTerminal())   // blocks ops on delegated/fulfilled commitments
        .map(c -> c.channelId)
        .filter(id -> id != null);
}
```

The `!c.state.isTerminal()` filter restores the guard that `channelMap.remove()` provided
implicitly. When `escalate()` or `delegate()` dispatches HANDOFF, Qhorus transitions the
commitment to DELEGATED — a terminal state for the original obligor (Commitment entity
Javadoc: "On HANDOFF, the original Commitment transitions to DELEGATED"; PLATFORM.md:
"Qhorus DELEGATED is terminal for the original obligor"). A subsequent `done()` from the
original agent finds the commitment in terminal state, `resolveChannelId()` returns empty,
and `done()` falls to `selfCommit_done()`.

**`selfCommit_done()` and `selfCommit_reject()` must check commitment state.** When
`resolveChannelId()` returns empty for a DELEGATED commitment, `done()` and `reject()` both
fall through to their self-commit equivalents. Neither currently checks state — they would
attempt to fulfill/decline an already-terminal commitment. Both need a state guard:

```java
private ToolResponse selfCommit_done(String correlationId) {
    Optional<Commitment> existing = commitmentStore.findByCorrelationId(correlationId);
    if (existing.isEmpty()) {
        return ToolResponse.error("COMMITMENT_NOT_FOUND: " + correlationId);
    }
    if (existing.get().state.isTerminal()) {
        return ToolResponse.error("COMMITMENT_ALREADY_CLOSED: " + correlationId
                + " is in state " + existing.get().state);
    }
    commitmentService.fulfill(correlationId);
    return ToolResponse.success("{\"closed\": true}");
}

private ToolResponse selfCommit_reject(String correlationId, String reason) {
    Optional<Commitment> existing = commitmentStore.findByCorrelationId(correlationId);
    if (existing.isEmpty()) {
        return ToolResponse.error("COMMITMENT_NOT_FOUND: " + correlationId);
    }
    if (existing.get().state.isTerminal()) {
        return ToolResponse.error("COMMITMENT_ALREADY_CLOSED: " + correlationId
                + " is in state " + existing.get().state);
    }
    commitmentService.decline(correlationId);
    return ToolResponse.success("{\"declined\": true}");
}
```

**End-to-end transition assumption:** The terminal filter works only if HANDOFF dispatch
auto-transitions the Qhorus Commitment to DELEGATED in the InMemory store. The unit test
for `resolveChannelId()` constructs a DELEGATED commitment manually — this tests the filter
in isolation but not the auto-transition. A separate `@QuarkusTest` is needed to verify
that a real `escalate()` or `delegate()` call followed by a real `done()` returns an error
via the InMemory store path. This is tracked but not implemented in this branch.

**Known trade-off — double DB read for unknown commitmentId in `done()`:** When a
completely unknown `commitmentId` is passed to `done()`, `findByCorrelationId()` is called
twice: once inside `resolveChannelId()` and once inside `selfCommit_done()`. Both return
empty → COMMITMENT_NOT_FOUND. This is a minor regression from the old code (one read via
`selfCommit_done()` only). Acceptable for now; a future refactor can consolidate the reads
if it becomes a concern.

**Two method groups — distinct treatment:**

*Mixed-path tools (`done`, `reject`)* — use `resolveChannelId()`, fall through to
self-commit handling if empty:
```java
return resolveChannelId(commitmentId)
    .map(channelId -> channelBacked_X(agentId, commitmentId, channelId, ...))
    .orElseGet(() -> selfCommit_X(commitmentId, ...));
```

*Channel-only tools (`checkpoint`, `escalate`, `delegate`)* — use `resolveChannelId()`,
return an error if empty:
```java
Optional<UUID> channelOpt = resolveChannelId(commitmentId);
if (channelOpt.isEmpty()) {
    return ToolResponse.error("COMMITMENT_NOT_FOUND: " + commitmentId);
}
UUID channelId = channelOpt.get();
```

**`block()` — single read, inline:** `block()` already reads the commitment for validation
(auth check, state, deadline update). Inline the channelId extraction from the same read:
```java
Optional<Commitment> cOpt = commitmentStore.findByCorrelationId(commitmentId);
if (cOpt.isEmpty()) {
    return ToolResponse.error("COMMITMENT_NOT_FOUND: " + commitmentId);
}
Commitment commitment = cOpt.get();
UUID channelId = commitment.channelId;   // cOpt.get() is safe after isEmpty() guard
// ... state/auth/deadline validation ...
// ... dispatch to channel if channelId != null ...
```

**`channelBacked_commit()`:** remove `channelMap.put(correlationId, channelId)`. The
STATUS dispatch at that call site uses `channelId` as a local variable — unaffected.

### Tests

**Broad test migration required.** Dropping the map breaks all existing channel-backed test
methods. These tests call `tools.commit(agentId, "task", null, channelId.toString())` to
populate `channelMap`. After the map is gone, `resolveChannelId()` calls
`commitmentStore.findByCorrelationId()`, which is not stubbed in these tests → Mockito
returns `Optional.empty()` → falls to self-commit path → COMMITMENT_NOT_FOUND.

Migration: replace `tools.commit(...)` setup with a direct stub:
```java
when(commitmentStore.findByCorrelationId(correlationId))
    .thenReturn(Optional.of(commitment(correlationId, channelId, agentId, deadline)));
```

Affected test methods (approximately 10-12):
- `done_channelBacked_*`
- `done_channelBacked_commandMessageNotFound_returnsError`
- `reject_dispatchesDeclineToChannel`
- `checkpoint_dispatchesStatusWithNote`
- `escalate_dispatchesHandoffAndRemovesFromMap` → rename: `escalate_dispatchesHandoffToChannel`
- `delegate_dispatchesHandoff*` → rename to remove "channelMap" references
- `delegate_removesFromChannelMapAfterDispatch` → rename: `delegate_dispatchesHandoffToChannel`;
  replace map-clearing assertion with: after delegate(), call done() with mock returning
  DELEGATED state, verify done() returns COMMITMENT_ALREADY_CLOSED error
- `block_channelBacked_*`
- `block_dispatchThrowsAfterSave*`

**New unit tests for `resolveChannelId()`:**
- Returns `Optional.of(channelId)` for non-terminal commitment with non-null channelId
- Returns `Optional.empty()` for non-terminal commitment with null channelId
- Returns `Optional.empty()` for terminal commitment (FULFILLED or DELEGATED)
- Returns `Optional.empty()` when `findByCorrelationId()` returns empty

**New behavioral tests for post-escalation guard (two methods — `done` and `reject`):**

Both tests must fully set up `escalate()` so it actually dispatches HANDOFF and returns
success. `escalate()` calls `findCommandMessageId()` which calls
`messageService.findAllByCorrelationId()` — if this is not stubbed, Mockito returns an
empty list, `escalate()` returns COMMAND_NOT_FOUND, and the test passes for the wrong
reason (the DELEGATED re-stub fires unconditionally regardless). Every assertion must be
load-bearing.

```java
// Given: OPEN commitment with real channelId
when(commitmentStore.findByCorrelationId(correlationId))
    .thenReturn(Optional.of(openCommitment(correlationId, channelId, agentId)));

// Required for escalate() to find the COMMAND message and dispatch HANDOFF
when(messageService.findAllByCorrelationId(correlationId))
    .thenReturn(List.of(message(5L, channelId, MessageType.COMMAND, correlationId)));
when(messageService.dispatch(any()))
    .thenReturn(dispatchResult(11L, channelId, agentId, MessageType.HANDOFF, correlationId));

// When: escalate — assert it actually succeeded (not a silent failure)
ToolResponse escalateResult = tools.escalate(agentId, correlationId, "reason", "other-agent");
assertThat(escalateResult.isError()).isFalse();

// Simulate Qhorus transitioning commitment to DELEGATED on HANDOFF
// (production: auto-transition via Commitment entity Javadoc; unit: manual re-stub)
when(commitmentStore.findByCorrelationId(correlationId))
    .thenReturn(Optional.of(delegatedCommitment(correlationId, channelId, agentId)));

// Then: done() must return COMMITMENT_ALREADY_CLOSED, not dispatch DONE
ToolResponse doneResult = tools.done(agentId, correlationId, null);
assertThat(doneResult.isError()).isTrue();
assertThat(text(doneResult)).contains("COMMITMENT_ALREADY_CLOSED");
verify(messageService, never()).dispatch(argThat(d -> MessageType.DONE == d.type()));
```

Mirror the same setup for `reject()` to cover `selfCommit_reject()`'s state guard:
replace the `done()` call and assertion with `tools.reject(agentId, correlationId, "reason")`
and verify COMMITMENT_ALREADY_CLOSED and no DECLINE dispatch.

These tests verify the `resolveChannelId()` terminal filter in the end-to-end tool path.
The Qhorus state transition is manually re-stubbed — unavoidable in a unit test and
explicitly documented as an assumption requiring a future `@QuarkusTest` for end-to-end
coverage.

**Known error-code inconsistency:** Channel-only tools (`checkpoint`, `escalate`, `delegate`)
return COMMITMENT_NOT_FOUND when `resolveChannelId()` returns empty — including for
terminal commitments. Mixed-path tools (`done`, `reject`) return COMMITMENT_ALREADY_CLOSED
via the self-commit state guard. Same root cause (commitment in terminal state), different
error codes. This is a deliberate limitation of `resolveChannelId()` returning empty
without distinguishing "not found" from "found but terminal." Acceptable for now; a future
refactor could make the distinction explicit if it causes operator confusion.

**Constructor:** remains 3 args — no new mocks needed.

---

## openclaw#22 — CDI wiring + fail-open test for `OversightGateDispatcher`

### Problem and scope

The existing `OversightGateDispatcherTest` is a plain unit test; `@Transactional` is not
active. This issue adds a `@QuarkusTest` verifying CDI wiring and fail-open behaviour —
**not JTA atomicity**. Class name: `OversightGateDispatcherCdiTest`.

**Why `@QuarkusTest`?** CDI wiring is what needs verification: that `@Transactional` is
container-resolved, that `OversightGateService.fulfill()` catches exceptions from
`gateDispatcher.dispatch()` in the live CDI context, and that the fail-open contract holds
with real bean lifecycle. A unit test with a mock `gateDispatcher` would verify none of this.

**What the test cannot prove:** JTA rollback. `InMemoryMessageStore` writes are immediate
and not rolled back on JPA transaction rollback. The first `dispatch()` RESPONSE remains in
the InMemory store even though production JPA would roll it back.

### Design

New test class `OversightGateDispatcherCdiTest` in `io.casehub.openclaw.app`, reusing
existing infrastructure (`casehub-qhorus-testing`, `quarkus-junit-mockito`).

**Class fields** — all shared state between `@BeforeEach` and test methods must be class
fields (not local variables in `@BeforeEach`). `OpenClawHookClient` must be mocked even
though `fulfill()` never calls it: Quarkus wires CDI at context startup before any test
method runs, and without a real base URL configured for the REST client, startup fails:
```java
@Inject OversightGateService oversightGateService;
@Inject ChannelService channelService;
@Inject MessageStore messageStore;     // required for assertion 2
@InjectSpy MessageService messageService;
@InjectMock @RestClient OpenClawHookClient hookClient;   // CDI wiring — fulfill() never calls it

UUID caseId;
UUID gateId;
Channel oversightChannel;
Channel workChannel;
```

**Channel and Commitment setup — `@BeforeEach`:**

Channel names must match exactly what `CaseChannelNames` produces, otherwise `fulfill()`'s
channel lookups fail:
```java
caseId = UUID.randomUUID();
gateId = UUID.randomUUID();

oversightChannel = channelService.create(
    "case-" + caseId + "/oversight", "Oversight", ChannelSemantic.APPEND, null);
workChannel = channelService.create(
    "case-" + caseId + "/work", "Work", ChannelSemantic.APPEND, null);
```

The Commitment is created implicitly: dispatching a COMMAND with non-null `correlationId`
to the oversight channel causes Qhorus InMemory to auto-create a Commitment with
`channelId = oversightChannelId` and `correlationId = gateId`.

**`findAllByCorrelationId` bridge — required in `@BeforeEach`:**

Without this, `fulfill()` queries Panache/H2 (finds nothing — test writes to InMemory):
```java
doAnswer(invocation -> {
    String correlationId = invocation.getArgument(0);
    return messageStore.scan(MessageQuery.builder().build()).stream()
            .filter(m -> correlationId.equals(m.correlationId))
            .sorted(Comparator.comparingLong(m -> m.id))
            .toList();
}).when(messageService).findAllByCorrelationId(any());
```

**Test method — stub sequencing is critical:**
```java
// 1. Real setup COMMAND — ActorType.AGENT to match production; no dispatch stub yet
messageService.dispatch(MessageDispatch.builder()
    .channelId(oversightChannel.id)
    .sender("openclaw-gate")
    .type(MessageType.COMMAND)
    .content("proposed action")
    .correlationId(gateId.toString())
    .actorType(ActorType.AGENT)
    .build());

// 2. Reset invocation count — verify(times(2)) counts only the two fulfill-path dispatches
clearInvocations(messageService);

// 3. Configure dispatch stub: first real (RESPONSE), second throws (STATUS attempt)
doCallRealMethod()
    .doThrow(new RuntimeException("simulated second-dispatch failure"))
    .when(messageService).dispatch(any());

// 4. Trigger fulfill
oversightGateService.fulfill(gateId, "approved");
```

**Assertions:**

```java
// 1. fulfill() returned without throwing (fail-open)
//    — implicit: if fulfill() threw, the test would already have failed

// 2. Work channel has no GATE_SENDER STATUS — second dispatch threw before any write
List<Message> workStatus = messageStore.scan(MessageQuery.builder().build()).stream()
    .filter(m -> workChannel.id.equals(m.channelId)
              && MessageType.STATUS == m.messageType
              && OversightGateService.GATE_SENDER.equals(m.sender))
    .toList();
assertThat(workStatus).isEmpty();

// 3. Exactly two fulfill-path dispatch calls attempted
verify(messageService, times(2)).dispatch(any());
```

**Limitation noted in Javadoc:** InMemory store retains the RESPONSE from the first
dispatch despite JPA transaction rollback. A JPA-backed store would enable asserting the
oversight channel also has no RESPONSE from `GATE_SENDER`.

One test method: `second_dispatch_failure_leaves_work_channel_empty_and_fulfill_is_fail_open`.

---

## Out of scope

`qhorus#250` (CommitmentService.extendDeadline()) is in a peer repo. Filed and tracked
there; no changes in this branch.
