---
layout: post
title: "Why associate() was the wrong API, and what replaced it"
date: 2026-05-29
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-openclaw]
tags: [quarkus, casehub, cdi, spi, channel-backend]
---

The previous entry ended with `associate(agentId, channelIds)` as the registration mechanism — WorkerProvisioner knows the agentId, knows the channels, calls associate. Tidy in theory. In practice: the provisioner knows the agentId at provision time, and CaseChannelProvider knows the channelIds at channel-open time, and there is no moment when both are available simultaneously in the same call site.

So I changed the API before writing a line of implementation.

The new model: two independent writes, one join at query time.

```java
service.bindAgent(agentId, caseId);    // called by WorkerProvisioner
service.bindChannel(caseId, channelId); // called by CaseChannelProvider
```

`query(agentId)` resolves to `agentToCase.get(agentId)`, then `caseChannels.get(caseId)`. Neither caller needs to know what the other is doing. The service joins at read time.

This also fixes a subtle regression I hadn't noticed in the original design: messages that arrive on a channel after `bindChannel()` but before `bindAgent()` are now captured in the ring buffer. The old `associate()` only initialised the buffer when both pieces arrived together — meaning early messages could be dropped in a race. The new approach initialises the buffer immediately on `bindChannel()`, so whatever order events arrive, nothing falls through.

The four CaseHub SPIs followed from there. WorkerProvisioner calls `bindAgent`, CaseChannelProvider calls `bindChannel` and creates the three normative Qhorus channels — work, observe, oversight, each with appropriate speech-act constraints. ChannelBackend filters incoming messages to COMMAND-only before invoking the OpenClaw hook. WorkerStatusListener cleans up both maps when the engine signals completion.

One piece was unexpectedly elegant. For ChannelBackend to register with Qhorus's fanOut gateway, the obvious approach is a startup listener that iterates all persisted channels. Instead, Qhorus already fires `ChannelInitialisedEvent` on every startup for all persisted channels — a startup recovery mechanism it ships for exactly this purpose. Observing that event with a channel-name filter gives free registration and free startup recovery in about ten lines.

The build then surfaced something I should have anticipated. Changing the dependency from `casehub-engine` (the full runtime artifact) to implementing the SPIs caused 31 CDI deployment failures at startup — all pointing at internal engine persistence beans (`CaseInstanceRepository`, `JobScheduler`, `WorkerExecutionManager`) that no one was providing. The error messages pointed everywhere except the cause.

The cause: `casehub-engine` is the full Quarkus runtime, CDI beans and all. `casehub-engine-api` is the pure-Java SPI interfaces with nothing else. An implementor needs the API, not the runtime. Swapping the dependency dropped 31 errors to zero.

Code review caught something I missed. In `OpenClawChannelBackend.post()`:

```java
String sessionKey = registry.findSessionKey(agentId)
    .orElseThrow(() -> new IllegalStateException("No session key for agentId: " + agentId));
```

That's outside the `try/catch` that handles `OpenClawInvocationException`. The `post()` method must never throw — Qhorus's fanOut catches exceptions from non-default backends, but `IllegalStateException` propagates. Any timing window between the three sequential writes in `registry.register()` would trigger it and abort the entire fanOut. Replaced with a log-and-return.

The integration is wired end-to-end now. A CaseHub case step dispatches a COMMAND to a Qhorus channel. ChannelBackend receives it, invokes the OpenClaw hook. OpenClaw delivers to the webhook endpoint. The endpoint dispatches DONE. The Qhorus message signal bridge notifies the engine. The engine resumes. The Python SDK injects channel context from the window before the agent's next turn.
