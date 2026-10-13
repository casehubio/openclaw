---
layout: post
title: "Three hardening passes on one branch"
date: 2026-06-26
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-openclaw]
tags: [completable-future, spi, oidc, mcp]
---

The DirectCallBridge from the previous session had a known gap: no eviction for orphaned `CompletableFuture`s. If a webhook never arrives and the subscriber doesn't cancel, the map entry leaks. The fix is `CompletableFuture.orTimeout()` — arm each future at submission time, and `whenComplete` removes it from the map on any terminal state. One cleanup path instead of three.

The interesting part was a precision bug we nearly missed. The natural composition is `timeout.toSeconds()` with `TimeUnit.SECONDS` — but `Duration.ofMillis(50).toSeconds()` truncates to zero. The timeout fires immediately, and tests pass by accident because the future is already completed exceptionally before the assertion runs. The fix is `toMillis()` with `TimeUnit.MILLISECONDS`. It's the kind of thing that works perfectly for production timeouts (120 seconds) and silently breaks for anything sub-second.

The `DirectCallDeliveryResource` — the only JAX-RS endpoint living in `casehub/` — moved to `app/` where every other endpoint lives. It only injects `DirectCallBridge`, which stays in `casehub/`. The boundary violation was always wrong; this just hadn't been fixed yet.

The second issue introduced `AgentProviderConfigSource` — an SPI in `casehub/` that abstracts where agent configuration comes from. Today a `@DefaultBean` in `app/` reads from `application.properties`. When the deployment module ships `agentIds()` enumeration, a second implementation will read from `DeploymentProviderConfigStore` instead, gated by `@IfBuildProperty`. The pattern is hexagonal: the library defines what it needs, the application wires the source. Three consumers migrated — both provisioners and the example controller.

MCP endpoint auth turned out to be the most architecturally interesting. The quarkus-mcp-server uses Vert.x routes, not JAX-RS, so `@RolesAllowed` on `@Tool` methods doesn't return HTTP status codes — it returns MCP error code `-32001`. For transport-level protection you need `quarkus.http.auth.permission.*`, which applies at the Vert.x routing layer and returns proper 401/403. Both layers complement each other: HTTP policy gates access, `@RolesAllowed` can provide per-tool authorization later.

One surprise in testing: an unauthenticated request to a path protected by `quarkus.http.auth.permission.*.policy=authenticated` returns HTTP 500, not 401, when the OIDC server is unreachable. The OIDC extension tries to validate even when there's no bearer token, fails to connect, and throws. `@RolesAllowed` on JAX-RS resources returns 401 without contacting the OIDC server because it operates at the CDI interceptor level. Different layers, different failure modes.
