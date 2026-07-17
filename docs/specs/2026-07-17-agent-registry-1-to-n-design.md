# OpenClawAgentRegistry 1:N Support

**Issue:** casehubio/openclaw#63
**Date:** 2026-07-17

## Problem

`OpenClawAgentRegistry.caseToAgent` is a `ConcurrentHashMap<UUID, String>` — one agent per case. When multiple agents register for the same caseId (e.g., casehub-life's 4 domain agents in a single case), later registrations overwrite earlier ones.

Worse: `deregister(agentA)` calls `caseToAgent.remove(caseId)`, which removes whatever is currently mapped — including `agentB` if B registered after A. Deregistering one agent corrupts another agent's mapping.

The `agentToCase` map (many→one) already supports 1:N. Only the reverse index `caseToAgent` and its cleanup logic are broken.

## Design

### Registry Map Change

```
- caseToAgent: ConcurrentHashMap<UUID, String>        // 1:1, lossy
+ caseToAgents: ConcurrentHashMap<UUID, Set<String>>  // 1:N, all agents tracked
```

Sets use `ConcurrentHashMap.newKeySet()` for thread safety.

### register()

```java
UUID previousCase = agentToCase.put(agentId, caseId);
if (previousCase != null && !previousCase.equals(caseId)) {
    Set<String> remaining = caseToAgents.computeIfPresent(previousCase, (k, agents) -> {
        agents.remove(agentId);
        return agents.isEmpty() ? null : agents;
    });
    if (remaining == null) {
        caseToTenancy.remove(previousCase);
    }
}
caseToAgents.computeIfAbsent(caseId, k -> ConcurrentHashMap.newKeySet()).add(agentId);
agentToSessionKey.put(agentId, sessionKey);
caseToTenancy.put(caseId, tenancyId);
```

If `agentId` was previously mapped to a different case, removes it from the old case's agent set before adding to the new one. When the agent was the last one for the old case, also removes the old case's tenancy entry. Mirrors the `deregister()` cleanup pattern across all correlated maps — prevents both orphaned set entries and orphaned tenancy entries.

Remove the MVP 1:1 constraint warning — multiple agents per case is now expected.

### deregister()

```java
public record DeregistrationResult(UUID caseId, boolean wasLastAgent) {}

public DeregistrationResult deregister(String agentId) {
    UUID caseId = agentToCase.remove(agentId);
    boolean wasLastAgent = false;
    if (caseId != null) {
        Set<String> remaining = caseToAgents.computeIfPresent(caseId, (k, agents) -> {
            agents.remove(agentId);
            return agents.isEmpty() ? null : agents;
        });
        wasLastAgent = (remaining == null);
        if (wasLastAgent) {
            caseToTenancy.remove(caseId);
        }
    }
    agentToSessionKey.remove(agentId);
    return new DeregistrationResult(caseId, wasLastAgent);
}
```

Returns `DeregistrationResult` with the case ID and whether this was the last agent for that case. `wasLastAgent` is derived from `computeIfPresent`'s return value — null means the set was evicted (last agent removed). Eliminates the TOCTOU between a separate `deregister()` call and a `hasAgentsForCase()` check.

`caseToTenancy` is only removed when no agents remain for the case. This map is pre-built routing infrastructure: the planned parallel COMMAND routing work (openclaw#70) requires tenancy context for multi-tenant agent dispatch. `closeCase()` is idempotent, so duplicate calls from concurrent deregistrations are safe.

### New: findAgentIds(caseId)

```java
public Set<String> findAgentIds(UUID caseId) {
    Set<String> agents = caseToAgents.get(caseId);
    return agents != null ? Set.copyOf(agents) : Set.of();
}
```

Returns a defensive copy. Replaces `findAgentId` as the primary lookup for consumers that need all agents.

### findAgentId(caseId) — transitional, kept for ChannelBackend

Returns `Optional` of any single agent from the set. This is a **transitional API** — `findAgentIds()` should be preferred by new callers. Retained because `OpenClawChannelBackend.post()` currently routes COMMANDs to a single agent; correct multi-agent routing is deferred to openclaw#70. Logs a warning if the set has >1 entries to signal that parallel routing is needed.

### hasAgentsForCase(caseId)

```java
public boolean hasAgentsForCase(UUID caseId) {
    return caseToAgents.containsKey(caseId);
}
```

General-purpose query for callers that need to check agent presence outside the deregister path. The primary case-closure mechanism uses `DeregistrationResult.wasLastAgent()` from `deregister()`, not this method.

## Caller Updates

### OpenClawChannelBackend.post()

No change. Continues using `findAgentId(caseId)`. With multiple agents registered, the COMMAND reaches an arbitrary agent — this is a known limitation. Correct multi-agent routing is tracked in openclaw#70.

### OpenClawWorkerStatusListener.onWorkerCompleted()

Must check whether other agents remain before closing the case:

```java
var result = registry.deregister(workerId);
service.unbindAgent(workerId);
if (result.caseId() != null && result.wasLastAgent()) {
    service.closeCase(result.caseId());
}
```

`DeregistrationResult` captures both the caseId and the last-agent status atomically — no pre-fetch of caseId needed, no separate `hasAgentsForCase()` check. Without this guard, closing the case when any agent completes destroys the ChannelContextWindow for remaining agents.

## Test Plan

### OpenClawAgentRegistryTest

Existing tests preserved (round-trip, deregister, tenancy, session key update).

New tests:
- `register_multipleAgents_sameCaseId_allTracked` — register A and B for same case, verify `findAgentIds` returns both
- `register_reRegisterDifferentCase_cleansOldCaseSet` — register A for case X, re-register A for case Y, verify case X set is empty, `findTenancyId(caseX)` is empty, and case Y has A
- `deregister_oneOfTwo_otherSurvives` — register A and B, deregister A, verify B found, tenancy preserved
- `deregister_lastAgent_cleansCaseMappings` — register A, deregister A, verify case mappings gone (tenancy removed)
- `deregister_returnsDeregistrationResult_withCaseId` — verify result contains correct caseId
- `deregister_lastAgent_wasLastAgentTrue` — verify `wasLastAgent` true when last agent removed
- `deregister_otherAgentsRemain_wasLastAgentFalse` — register A and B, deregister A, verify `wasLastAgent` false
- `findAgentId_multipleAgents_returnsOne` — register A and B, verify `findAgentId` returns a non-empty Optional
- `findAgentIds_unknownCase_returnsEmptySet` — verify empty set for unknown caseId
- `hasAgentsForCase_afterFullDeregister_returnsFalse` — register A, deregister A, verify false
- `concurrent_registerAndDeregister_noOrphanedEntries` — stress test: N threads register/deregister agents for overlapping cases via CountDownLatch barrier; verify no orphaned set entries and consistent map state after all threads complete

### OpenClawWorkerStatusListenerTest

- `onWorkerCompleted_otherAgentsRemain_doesNotCloseCase` — register A and B, complete A, verify `closeCase` not called (uses `DeregistrationResult.wasLastAgent()`)
- `onWorkerCompleted_lastAgent_closesCase` — register A only, complete A, verify `closeCase` called (uses `DeregistrationResult.wasLastAgent()`)

## Scope

This issue fixes registry correctness only. Parallel COMMAND routing (ChannelBackend selecting the right agent when `findAgentIds` returns >1) is a separate concern — tracked as openclaw#70.

## Files Changed

### Production code
- `casehub/src/main/java/io/casehub/openclaw/casehub/OpenClawAgentRegistry.java` — 1:N data structure, `DeregistrationResult`, re-registration cleanup
- `casehub/src/main/java/io/casehub/openclaw/casehub/OpenClawWorkerStatusListener.java` — use `DeregistrationResult` for case-closure guard

### Javadoc updates (behavioral contract change)
- `casehub/src/main/java/io/casehub/openclaw/casehub/OpenClawWorkerProvisioner.java` — remove "MVP constraint: one OpenClaw agent per case"
- `casehub/src/main/java/io/casehub/openclaw/casehub/ReactiveOpenClawWorkerProvisioner.java` — mirror javadoc update
- `casehub/src/main/java/io/casehub/openclaw/casehub/OpenClawAgentRegistry.java` — remove "1:1 caseId ↔ agentId" and "silently overwrites" javadoc

### Architecture documentation
- `ARC42STORIES.MD` — §2 Constraints (update "last-write-wins" note), §4 Solution Strategy Registry row (note 1:N reverse index), §5 Building Block View registry description, §9 registry file description

### Tests
- `casehub/src/test/java/io/casehub/openclaw/casehub/OpenClawAgentRegistryTest.java` — new 1:N tests, re-registration cleanup, `DeregistrationResult`, concurrency stress test
- `casehub/src/test/java/io/casehub/openclaw/casehub/OpenClawWorkerStatusListenerTest.java` — multi-agent case-closure guard tests
