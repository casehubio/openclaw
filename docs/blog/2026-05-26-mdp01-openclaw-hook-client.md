---
layout: post
title: "The hook client, a dead branch, and two bugs found late"
date: 2026-05-26
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-openclaw]
tags: [quarkus, rest-client, integration-testing, casehub]
---

A Quarkus library module with no end users, no versioning commitment, no backward compat — that's the license to design cleanly. The OpenClaw hook API client is the first real implementation in casehub-openclaw, and it's the foundation everything else in the integration depends on.

The core is `OpenClawHookClient`, an `@ApplicationScoped` CDI bean that owns two things: an HTTP client for `POST /hooks/agent` and `POST /hooks/wake`, and an in-memory session registry. The registry maps OpenClaw agent IDs to session state — a session key generated at provision time, a webhook delivery URL, nothing else. When a case step invokes an agent, it calls `invoke(agentId, message, model, timeout)`. The client looks up the session, constructs the request, posts it.

One design choice worth noting: the registry is keyed by agentId, not by CaseHub's workerId. That means last-write-wins if two cases try to use the same agent simultaneously. It's the same limitation Claudony carries for tmux sessions, and the same upstream fix will eventually resolve both — CaseHub's engine needs to include workerId in WorkResult before concurrent workers can be distinguished properly. For now: one active instance per agentId.

The `AgentInvocationRequest` record has a `forWebhook()` package-private static factory. The `deliver` field is always `"webhook"` for casehub-openclaw, but nothing in Java's type system enforces that — so callers outside the package can't construct the record directly. Enforce the invariant at the boundary, not in the error message.

Bearer auth registers via `@RegisterProvider` on the `@RegisterRestClient` interface, not via `RestClientBuilder.newBuilder()`. This matters: Quarkus only applies CDI-registered providers when you use CDI injection. The programmatic builder ignores them silently.

Then the WireMock integration test ran.

The status check `if (response.getStatus() / 100 != 2)` — that branch is dead code when a real server returns 5xx. Quarkus MicroProfile REST Client throws `ClientWebApplicationException` on non-2xx. It does not return a Response with the error status. The unit tests passed because they mock the gateway to return a mock Response with status 503 — the mock bypasses the exception entirely. Only the WireMock test, hitting a real HTTP server, revealed what the client actually does.

The fix is a `catch (WebApplicationException e)` with a null guard:

```java
int status = e.getResponse() != null ? e.getResponse().getStatus() : -1;
```

The JAX-RS spec doesn't guarantee `getResponse()` is non-null even in `WebApplicationException`, so the naive `e.getResponse().getStatus()` is wrong even as a fix.

Companion surprise: `jakarta.ws.rs.core.Response` has a `close()` method (added in JAX-RS 2.0) but doesn't implement `AutoCloseable`. Try-with-resources doesn't compile. The fix is `try { ... } finally { response.close(); }` — not elegant, but correct.

Before the branch merged, Claude caught two gaps in `app/application.properties`: the bearer-token config key was `gateway.token` instead of `gateway.bearer-token` (SmallRye auto-converts `bearerToken()` to `bearer-token`, not `token`), and the REST client URL bridge was missing entirely — `quarkus.rest-client.openclaw-gateway.url=${casehub.openclaw.gateway.url}`. Both would have caused startup failures. Neither surfaced in tests because the WireMock resource overrides both values at test time.

The hook client is in. Next is the ChannelContextWindow — the ring buffer that makes OpenClaw agents aware of what other agents have posted to Qhorus channels between turns.
