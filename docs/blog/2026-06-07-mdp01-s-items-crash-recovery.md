---
layout: post
title: "S-items: crash recovery, CDI wiring, channel types"
date: 2026-06-07
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-openclaw]
tags: [qhorus, mcp-tools, commitmenttools, channelspec, quarkustest]
---

Three small issues, one branch. I've been calling them "S-items" in the handover — `#20` (CommitmentTools crash recovery), `#22` (CDI wiring test), `#25` (oversight channel type constraints). Nothing architecturally new. But the spec took five iterations before we wrote a line of code, and I came out of it with a clearer picture of where some of the codebase's hidden assumptions had been silently accumulating.

**#25 was straightforward once claudony#142 closed.** The oversight channel had been created with `allowedTypes = null` — a deliberate deferral pending Claudony's design decision on the normative channel layout. That decision had been sitting as an open comment in the source since the Epic 4 work. PLATFORM.md was already clear on the answer (`deniedTypes = EVENT`), so we updated the channel provider.

The more interesting part was replacing the `String[]` LAYOUT map. The original code indexed channel config by position — `spec[0]` for description, `spec[1]` for allowedTypes. Adding a third element for deniedTypes exposed the pattern as a maintenance trap; the next engineer reading that code would have to count. We replaced it with a `private record ChannelSpec(String description, String allowedTypes, String deniedTypes)` nested inside the channel provider. Named accessors, no positional indexing. The record is private and lives in the one class that uses it — no over-engineering, just the right type for the data.

**#20 was filed as "store channelId on the casehub-engine Commitment entity."** That was wrong. The Qhorus `Commitment` entity already carries `channelId` — `OversightGateService.fulfill()` reads it at line 237. The issue description had identified the symptom (crash gap) but prescribed the wrong fix.

The actual problem was simpler: `CommitmentTools` kept a `ConcurrentHashMap<String, UUID>` mapping `correlationId → channelId`. On Quarkus restart, the map is empty. Tools fail with COMMITMENT_NOT_FOUND on perfectly valid commitments. The fix is to drop the map and read `channelId` from the persisted entity on every call.

I argued this was a premature optimisation. MCP tool calls happen at AI agent turn latency — hundreds of milliseconds to seconds. One extra DB read is invisible. The map's cost — the crash gap, the maintenance burden, the wrong fix in the issue — was not worth it.

The spec took longer to get right than the implementation. The review caught four things I'd missed. The `orElseReturn` call I'd sketched as pseudo-code doesn't exist on Java's `Optional` — a compile error the implementer would have blamed on the wrong thing. The spec incorrectly claimed that `selfCommit()` passes a random UUID as channelId; actually it passes null (the random UUID is the entity PK). The `@Column(nullable = false)` constraint on `Commitment.channelId` means self-commits would fail in production anyway, which changes the reasoning about why `resolveChannelId()` returns empty for them. And without a `!c.state.isTerminal()` filter, the escalate/delegate behavioral guard disappears — after handing off a commitment, the original agent could still call `done()` and dispatch DONE to the channel.

All of these are things I should have caught before drafting the spec. I didn't. The review loop exists for a reason.

`resolveChannelId()` ended up clean:

```java
private Optional<UUID> resolveChannelId(String correlationId) {
    return commitmentStore.findByCorrelationId(correlationId)
            .filter(c -> !c.state.isTerminal())
            .map(c -> c.channelId)
            .filter(id -> id != null);
}
```

Mixed-path tools (`done`, `reject`) use `resolveChannelId().map(...).orElseGet(selfCommit_*)`. Channel-only tools (`checkpoint`, `escalate`, `delegate`) use it with an isEmpty guard and a hard COMMITMENT_NOT_FOUND. There's a documented asymmetry: a terminal commitment returns COMMITMENT_NOT_FOUND from channel-only tools but COMMITMENT_ALREADY_CLOSED from mixed-path tools. Both come from `resolveChannelId()` returning empty — but the mixed-path fallthrough hits `selfCommit_done()`'s state guard, which has the right error. The channel-only tools don't have a fallthrough. We documented it as deliberate and added tests locking in the known behavior so a future refactor changes it explicitly.

**#22 was a naming problem as much as an implementation problem.** The test was originally called `OversightGateDispatcherAtomicityTest`. But `InMemoryMessageStore` from `casehub-qhorus-testing` uses a `ConcurrentHashMap` — writes are immediate and don't participate in JTA transactions. There's no way to verify that the first dispatch was rolled back when the second throws. The test proves CDI wiring is correct and that `fulfill()` is fail-open; it doesn't prove atomicity. We renamed it `OversightGateDispatcherCdiTest`.

The test is still useful. Fail-open is a real contract: when `gateDispatcher.dispatch()` throws mid-way, `fulfill()` must catch the exception and return normally rather than propagating. The work channel must stay empty — the second dispatch (STATUS to work channel) threw before any write. The only JTA claim is the one we can't test with InMemory infrastructure, and the Javadoc says so.

Two garden entries came out of this: a gotcha on Mockito's silent behaviour when you upgrade a method from N parameters to N+1 and forget to add the extra `any()` matcher to existing stubs (the stub matches nothing, Mockito returns null, the NPE appears in production code), and a technique for the `clearInvocations`/spy-stub sequencing pattern that makes `verify(times(N))` count only the production-path dispatches.

One protocol: `PP-20260607-84b26d` — no in-memory caches for entity associations in `@ApplicationScoped` MCP tool beans. At agent turn latency, a DB read costs nothing. The map costs the crash gap, the spec complexity, and the wrong fix in the issue. Not worth it.
