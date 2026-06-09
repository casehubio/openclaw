# Reactive SPI Variants — Design Spec

**Date:** 2026-06-09  
**Issue:** openclaw#12  
**Branch:** issue-12-reactive-spi-variants  
**Protocol refs:** PP-20260519-39a9a5 (reactive-build-gating), PP-20260519-5f6d9f (claudony-reactive-spi-variants)

---

## Context

casehub-engine's `CaseContextChangedEventHandler` runs on the Vert.x IO thread and injects
`ReactiveWorkerProvisioner` and `ReactiveCaseChannelProvider`. Blocking SPI implementations
(`WorkerProvisioner`, `CaseChannelProvider`) are not called on this path. When casehub-openclaw
is deployed in a reactive stack, it must provide reactive implementations or the engine
falls back to the no-op defaults, leaving agents unprovisioned.

Claudony has the reference implementation: `ClaudonyReactiveWorkerProvisioner` and
`ClaudonyReactiveCaseChannelProvider` (claudony#115).

---

## Approach

Two independent `@ApplicationScoped` beans, one per SPI. Both gated by
`@IfBuildProperty(name="casehub.qhorus.reactive.enabled", stringValue="true")` — the same
property that gates `ReactiveChannelService` and `ReactiveMessageService`. One flag, no
co-deployment risk.

The blocking beans (`OpenClawWorkerProvisioner`, `OpenClawCaseChannelProvider`) need no
`@UnlessBuildProperty` annotation — they implement `WorkerProvisioner` and `CaseChannelProvider`
respectively, while the reactive beans implement `ReactiveWorkerProvisioner` and
`ReactiveCaseChannelProvider`. Different interfaces means no CDI ambiguity. The engine's no-op
defaults carry `@DefaultBean`, which yields automatically to any `@ApplicationScoped` implementation
of the same interface without an `@Alternative` qualifier.

---

## ReactiveOpenClawWorkerProvisioner

**Package:** `io.casehub.openclaw.casehub`  
**Implements:** `io.casehub.api.spi.ReactiveWorkerProvisioner`  
**Gate:** `@IfBuildProperty(name="casehub.qhorus.reactive.enabled", stringValue="true")`

All provisioner operations touch only `ConcurrentHashMap` structures in `OpenClawAgentRegistry`
and `ChannelContextWindowService` — no I/O. `Uni.createFrom().item(Supplier)` is used throughout
so exceptions from `resolveAgentId()` propagate as Uni failures rather than being thrown
synchronously.

```
provision(capabilities, context)
  → Uni.createFrom().item(() → {
        agentId = resolveAgentId(capabilities)              // throws ProvisioningException → Uni failure
        sessionKey = config.agents().get(agentId).sessionKey()
        registry.register(agentId, caseId, sessionKey)
        service.bindAgent(agentId, caseId)
        return ProvisionResult.empty()
    })

terminate(workerId)
  → Uni.createFrom().runnable(() → registry.deregister(workerId))
    // Uni.createFrom().runnable() already returns Uni<Void> — no .replaceWithVoid() needed

getCapabilities()
  → Uni.createFrom().item(() → config.agents().values().stream()
        .flatMap(e → e.capabilities().stream()).collect(toSet()))
```

`resolveAgentId()` is identical to the blocking variant (first alphabetical agent whose
capability set is a superset of the requested capabilities).

---

## ReactiveOpenClawCaseChannelProvider

**Package:** `io.casehub.openclaw.casehub`  
**Implements:** `io.casehub.api.spi.ReactiveCaseChannelProvider`  
**Gate:** `@IfBuildProperty(name="casehub.qhorus.reactive.enabled", stringValue="true")`  
**Deps:** `ReactiveChannelService`, `ReactiveMessageService`, `ChannelContextWindowService`,
`ChannelGateway`

### gateway.initChannel() is required after channel creation

Neither `ChannelService.create()` (blocking) nor `ReactiveChannelService.create()` (reactive)
calls `ChannelGateway.initChannel()` — both persist only via `channelStore.put()`. The gateway
javadoc says "Called by create_channel and by the startup hook" — "create_channel" refers to
callers, not to the service method itself. `ChannelGateway.onStart()` fires
`ChannelInitialisedEvent` with `recovered=true` for all channels persisted at boot; channels
created after startup receive no event unless the caller fires it.

`OpenClawChannelBackend` registers with the gateway by observing `ChannelInitialisedEvent`.
If a channel is created during a running session without `initChannel()` being called, the
backend never registers for that channel, and COMMAND messages dispatched to it are silently
dropped. This is a latent defect in the existing blocking `OpenClawCaseChannelProvider` too
— fixing both on this branch.

After `channelService.create()`, call `gateway.initChannel(ch.id, new ChannelRef(ch.id, ch.name))`.
Do NOT call it on the `findByName` path — channels already in the DB were registered by the
startup hook.

### Memoized layout cache eliminates the race condition

Engine bindings can trigger concurrent `openChannel()` calls for the same case. A naive
`findByName → empty → create` sequence under concurrency causes both callers to attempt
`create()` simultaneously; one hits a unique-constraint violation.

Pattern from `ClaudonyReactiveCaseChannelProvider`:

```java
private final ConcurrentHashMap<UUID, Uni<Map<String, CaseChannel>>> layoutCache
    = new ConcurrentHashMap<>();

openChannel(caseId, purpose)
  → layoutCache.computeIfAbsent(caseId, id →
        initializeLayout(id)
            .onFailure().invoke(err → layoutCache.remove(id))
            .memoize().indefinitely())
      .map(channels → channels.get(purpose))
```

`computeIfAbsent` + `memoize().indefinitely()` guarantees `initializeLayout()` runs exactly
once per caseId per process lifetime. The failure handler removes the stale Uni so a retry
can succeed.

`initializeLayout()` creates all three channels eagerly in a sequential `flatMap` chain (the
same three channels the engine always requests: work / observe / oversight):

```
initializeLayout(caseId)
  → for each spec in LAYOUT:
      channelService.findByName(channelName)
          .flatMap(opt → opt.present
              ? Uni.item(opt.get())
              : channelService.create(name, desc, APPEND, null,null,null,null,null, allowed, denied)
                    .invoke(ch → gateway.initChannel(ch.id, new ChannelRef(ch.id, ch.name))))
          .invoke(ch → contextService.bindChannel(caseId, ch.id))
          .map(ch → acc.put(purpose, new CaseChannel(ch.id.toString(), ch.name, purpose,
                             "qhorus", Map.of(QHORUS_NAME_KEY, ch.name))))
```

`gateway.initChannel()` is invoked only on the create path, inside `invoke()` before the map
adds to the accumulator. `contextService.bindChannel()` is in-memory (safe in any thread context).

### Remaining methods

```
postToChannel(channel, from, content, type, correlationId, deadline)
  → effectiveType = type != null ? type : MessageType.STATUS
    messageService.dispatch(MessageDispatch.builder()
        .channelId(UUID.fromString(channel.id()))
        .sender(from).type(effectiveType).content(content)
        .correlationId(correlationId)
        .deadline(deadline != null ? Instant.parse(deadline) : null)
        .actorType(ActorType.AGENT)
        .build()).replaceWithVoid()

closeChannel(channel)
  → Uni.createFrom().voidItem()

listChannels(caseId)
  → channelService.findByNamePrefix("case-{caseId}/")
      .map(channels → channels.stream()
          .map(ch → new CaseChannel(ch.id.toString(), ch.name, extractPurpose(...), "qhorus",
                                    Map.of(QHORUS_NAME_KEY, ch.name)))
          .toList())
```

---

## Also: Fix blocking OpenClawCaseChannelProvider

The same `gateway.initChannel()` gap exists in `OpenClawCaseChannelProvider.openChannel()`.
Fix it on this branch: inject `ChannelGateway`, call `gateway.initChannel(channel.id, new ChannelRef(channel.id, channel.name))` on the create path only (not on the findByName path).

Add a test: `openChannel_newChannel_callsInitChannel` and verify `initChannel` is NOT called
on the existing-channel path.

---

## Tests

All unit tests — no `@QuarkusTest`, no container.

**ReactiveOpenClawWorkerProvisionerTest**  
Mocks: `ChannelContextWindowService` (Mockito), real `OpenClawAgentRegistry`, anonymous `OpenClawCasehubConfig`.  
Assertions via `UniAssertSubscriber` (`io.smallrye.mutiny.helpers.test`).

| Test | Covers |
|------|--------|
| `provision_singleCapabilityMatch_registersAgentInRegistry` | happy path, registry updated |
| `provision_callsBindAgent_onContextWindowService` | context window binding |
| `provision_registersSessionKey` | sessionKey from config after resolveAgentId |
| `provision_returnsEmptyProvisionResult` | Uni emits `ProvisionResult.empty()` |
| `provision_unknownCapability_emitsProvisioningExceptionFailure` | Uni failure, not thrown |
| `getCapabilities_returnsAllConfiguredCapabilities` | aggregates from config |
| `terminate_deregistersFromRegistry` | registry cleaned up |

**ReactiveOpenClawCaseChannelProviderTest**  
Mocks: `ReactiveChannelService`, `ReactiveMessageService`, `ChannelContextWindowService`, `ChannelGateway`.  
Mock returns: `Uni.createFrom().item(value)`.

| Test | Covers |
|------|--------|
| `openChannel_newChannel_callsCreate` | create path, CaseChannel fields |
| `openChannel_newChannel_callsInitChannel` | gateway.initChannel() on create path |
| `openChannel_existingChannel_returnsWithoutCreate` | idempotent — no create, no initChannel |
| `openChannel_callsBindChannel` | contextService.bindChannel() on both paths |
| `openChannel_cachePreventsDuplicateCreate_onConcurrentCalls` | memoize eliminates race |
| `openChannel_work_bothTypesNull` | LAYOUT constraints |
| `openChannel_observe_allowedTypesEvent` | LAYOUT constraints |
| `openChannel_oversight_deniedTypesEvent` | LAYOUT constraints |
| `postToChannel_dispatchesWithCorrectFields` | message dispatch |
| `postToChannel_nullType_defaultsToStatus` | STATUS default |
| `closeChannel_completesWithVoid` | no-op completes |
| `listChannels_delegatesToFindByNamePrefix` | list path |

**OpenClawCaseChannelProviderTest additions**

| Test | Covers |
|------|--------|
| `openChannel_newChannel_callsInitChannel` | gateway.initChannel() on create path |
| `openChannel_existingChannel_doesNotCallInitChannel` | no initChannel on find path |

---

## Issue #19 — Epic 7 MCP deep-dive

Epic 7 (openclaw#7) is CLOSED. Action: file an issue on `casehubio/parent` with the exact
`§Layer 0 — Quarkus MCP Endpoint` content to add to `docs/repos/casehub-openclaw.md`,
then close openclaw#19.

## Issue #18 — Upstream tracker

Nothing to implement. Remains open until OpenClaw closes upstream #60209.
