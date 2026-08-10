---
layout: post
title: "The silent failure hiding in the blocking code"
date: 2026-06-09
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-openclaw]
tags: [reactive, mutiny, qhorus, channel-gateway, bug]
---

The task for today's second session was supposed to be straightforward — implement reactive SPI variants for the OpenClaw provisioner and channel provider. S-scale, a few hours, done.

It was a few hours. But the straightforward part didn't survive contact with the code.

When I started designing `ReactiveOpenClawCaseChannelProvider`, I pulled up Claudony's reactive channel provider as the reference. Claudony has `createQhorusChannel()`, which creates a channel and then calls `gateway.initChannel()` after the DB write. I'd seen that pattern before but hadn't really looked at why. This time I went looking.

`ChannelService.create()` — both blocking and reactive — only calls `channelStore.put(channel)`. It does not fire `ChannelInitialisedEvent`. It does not call `gateway.initChannel()`. The javadoc on `ChannelGateway.initChannel()` says "Called by create_channel and by the startup hook" — which sounds like the service handles it internally. It does not. "Create_channel" means callers of the gateway method, not the service.

At startup, `ChannelGateway.onStart()` fires `initChannel` with `recovered=true` for every channel already in the database. But channels created after startup — channels opened by `CaseChannelProvider.openChannel()` when a new case starts — get nothing. `OpenClawChannelBackend` observes `ChannelInitialisedEvent` to register itself with the gateway. If the event never fires, the backend is never registered for that channel. `ChannelGateway.fanOut()` finds no registered non-default backends, iterates over an empty list, logs nothing, and returns. The COMMAND is gone.

What made this particularly unpleasant is that all the other signals look normal. The channel exists in the database. The message is persisted by `MessageService.dispatch()`. The HTTP call returns 200. The only way to know is if you notice that nothing is happening downstream — which in a case that's just started means waiting and wondering why the agent hasn't responded.

The existing blocking `OpenClawCaseChannelProvider.openChannel()` has this defect. Every case started in a fresh process creates new channels after startup, so every invocation on every channel is affected. The code has been running this way since the channel provider was implemented.

The fix is three lines: inject `ChannelGateway`, call `gateway.initChannel(created.id, new ChannelRef(created.id, created.name))` in the `orElseGet()` lambda, and not on the `findByName()` path (channels found by name already exist in the database and were covered by the startup hook).

For the reactive variant I also needed a layout cache. The engine can fire `openChannel()` for multiple purposes concurrently — work, observe, oversight — and a naive `findByName → empty → create` sequence races: two threads see the channel as absent, both call `create()`, one hits a unique-constraint violation. Claudony handles this with `ConcurrentHashMap.computeIfAbsent()` + `Uni.memoize().indefinitely()`. First call computes the Uni; all concurrent callers wait on the same Uni; the memoized result replays to everyone. The trick is that the failure eviction must appear before `memoize()` in the chain:

```java
initializeLayout(id)
    .onFailure().invoke(err -> layoutCache.remove(id))  // before memoize
    .memoize().indefinitely()
```

`memoize()` captures the first emission, success or failure. If a transient failure is memoized, it's permanent. With the eviction first, a failure removes the cache entry before `memoize()` locks it in, allowing the next caller to retry with a fresh Uni.

One thing I'd remembered wrong: `Uni.createFrom().runnable()` doesn't exist in this Mutiny version. I'd used it in some notes from other reactive work, apparently conflating it with Reactor's API. The actual form for `Uni<Void>` wrapping a synchronous side effect is `Uni.createFrom().<Void>item(() -> { ...; return null; })`. Fine, but non-obvious if you expect `runnable()`.

The code review also caught that gating both reactive beans on a separate `casehub.openclaw.reactive.enabled` flag (my original proposal) rather than directly on `casehub.qhorus.reactive.enabled` creates a co-deployment trap: set only the openclaw flag, and Quarkus augmentation fails with `UnsatisfiedResolutionException` because the reactive channel services the beans inject are gated on the qhorus flag and absent. One gate, expressing the dependency directly, is the right design.

This is now a protocol for integration-tier harnesses: reactive SPI implementations use the same `@IfBuildProperty` gate as the infrastructure they inject. Obvious in hindsight. The kind of thing that gets rediscovered every time someone adds a new harness if it isn't written down.
