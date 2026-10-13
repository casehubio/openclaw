---
layout: post
title: "Typed Adapters Over Untyped Registries"
date: 2026-06-30
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-openclaw]
tags: [spi, config, typed-boundary, platform-coherence]
---

The `ProvisionerConfigRegistry` SPI landed in engine-api with `Map<String, Object>` as its return type. Necessarily untyped — engine-api can't depend on every provider's config shapes. OpenClaw needs `sessionKey` and `capabilities` as typed fields, and the provisioner code shouldn't regress to string-key casting just because the transport layer is generic.

The question that shaped this branch: where does the untyped-to-typed conversion happen, and can we contain it?

I considered three approaches. Provisioners consuming the registry directly — simpler but scatters casting across every call site. Promoting capabilities to a first-class registry method in engine-api — arguably the right long-term design, but an engine-api change that doesn't belong in an openclaw migration ticket. And a typed resolver — a single concrete bean that wraps the registry and exposes `AgentConfig` records, containing the `Map<String, Object>` boundary in one place.

The resolver won. `OpenClawAgentConfigResolver` sits between `ProvisionerConfigRegistry` and the provisioners. It injects both the registry and the local `OpenClawCasehubConfig`, tries registry first with local-config fallback, and exposes typed `allAgents()`, `configFor()`, and `agentIds()`. The untyped map never leaks past `fromRaw()`.

The design review caught a real inconsistency: I'd written `allAgents()` with all-or-nothing semantics (if registry has anything, it wins entirely) but `configFor()` with per-agent fallback. A consumer calling `agentIds()` to discover agents and then `configFor()` for each one would get different results depending on which method it started with. Claude revised the spec to union semantics — local config merged with registry, registry wins per-agent — matching Claudony's existing `CompositeProviderConfigSource` pattern. Same platform, same SPI, same semantics.

The review also pushed for startup validation. `fromRaw()` now runs against every declared registry agent at pod boot via `@Observes StartupEvent`. Malformed entries fail the pod immediately — the same guarantee that `@ConfigMapping` gives for local config. When `NoOpProvisionerConfigRegistry` is active (dev, tests), `declaredAgentIds()` returns empty and the validation is a no-op.

Three files deleted: `AgentProviderConfigSource`, `ConfigFileAgentProviderConfigSource`, and its test. The old SPI's extension point — any `@Alternative` could displace the `@DefaultBean` — is deliberately not replicated. Override semantics move to the platform: the registry's own CDI layering handles deployment-time configuration.

The typed-resolver-over-untyped-transport pattern is probably reusable. Claudony will need the same thing when it migrates to the registry — a `ClaudonyAgentConfigResolver` with its own typed config shape, wrapping the same `Map<String, Object>` from the same SPI. The untyped registry is transport; the typed resolver is the provider's domain boundary. That separation is worth naming explicitly, because it will recur.
