---
layout: post
title: "The Reverse-Index Corruption Bug Nobody Hit Yet"
date: 2026-07-18
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-openclaw]
tags: [registry, concurrency, ConcurrentHashMap]
---

The `OpenClawAgentRegistry` had a silent data-corruption bug hiding behind a "works for now" 1:1 constraint. The registry maps agents to cases for channel routing — when a Qhorus COMMAND arrives, the backend needs to know which OpenClaw agent handles it. The mapping was `caseToAgent: ConcurrentHashMap<UUID, String>`, one agent per case.

That held for the demo scenarios, which run agents sequentially. But casehub-life needs four agents on the same case — health, home, finance, travel — each with distinct capabilities but sharing the same model family. The moment two agents register for the same case, the second silently overwrites the first.

Worse: `deregister()` called `caseToAgent.remove(caseId)`, which removes whatever value is currently mapped. If agent B registered after agent A, deregistering A destroys B's mapping. The caller doesn't know; the routing just breaks.

## The fix

We replaced `caseToAgent` with `caseToAgents: ConcurrentHashMap<UUID, Set<String>>` — a multimap using `ConcurrentHashMap.newKeySet()` for thread-safe sets. The interesting part was the cleanup semantics.

`deregister()` now returns a `DeregistrationResult(caseId, wasLastAgent)` record. The `wasLastAgent` flag comes from `computeIfPresent` — when the lambda removes the last agent and returns null, the map entry is atomically evicted. That null return IS the signal. No separate `hasAgentsForCase()` check, no TOCTOU window between the check and the act.

`register()` also needed cleanup for re-registration: if an agent moves from case X to case Y, the old case's set and tenancy entry must be cleaned. Same `computeIfPresent` pattern, mirrored across both maps.

The `OpenClawWorkerStatusListener` uses `DeregistrationResult.wasLastAgent()` to decide whether to close the case's `ChannelContextWindow`. Without this guard, completing any one agent destroys the context window for the others still running.

## What this doesn't solve

The `OpenClawChannelBackend` still uses `findAgentId(caseId)` — a transitional API that returns any single agent from the set. For the sequential execution model that's fine; there's at most one active agent per case at a time. For parallel execution — multiple agents working simultaneously on the same case — the backend needs to know which agent a specific COMMAND targets. That's tracked as openclaw#70.

The registry is now correct for 1:N. The routing layer isn't there yet.
