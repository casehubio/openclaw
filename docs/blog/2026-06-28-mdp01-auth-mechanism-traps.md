---
layout: post
title: "When your auth mechanism breaks everyone else's tests"
date: 2026-06-28
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-openclaw]
tags: [quarkus, security, cdi, auth]
---

Three issues landed on one branch: plugin endpoint auth (#42), a CDI cleanup (#48), and a documentation update (#47). The CDI cleanup was mechanical — `OidcCurrentPrincipal @Alternative @Priority(100)` shipped in platform#111, making the `exclude-types` entries for `MockCurrentPrincipal` and `QhorusInboundCurrentPrincipal` redundant. Bytecode confirmed the priority chain. Straightforward.

The plugin auth was not.

## The design looked clean

`PluginTokenBridgeMechanism` — a custom `HttpAuthenticationMechanism` that validates a pre-shared bearer token for `/openclaw/plugin/*` paths. `@RolesAllowed(OpenClawGroups.PLUGIN)` on `PluginCommitResource`. Quarkus `auth-mechanism=plugin-token` config routes plugin paths to the mechanism. `getCredentialTransport()` declares the scheme so Quarkus can match it. Standard API, documented in Javadoc.

Three things broke.

## auth-mechanism overrides @TestSecurity

The first surprise: `quarkus.http.auth.permission.*.auth-mechanism=plugin-token` causes Quarkus to route to the named mechanism before `AbstractTestHttpAuthenticationMechanism` can intercept. `@TestSecurity` — which runs at `Integer.MAX_VALUE` priority — never fires. Every functional test on plugin paths returned 500.

The Javadoc for `auth-mechanism` only describes matching against `getCredentialTransport().getAuthenticationScheme()`. Nothing about the interaction with `@TestSecurity`. The expectation that test mechanisms always win (they have the highest priority) is wrong — `auth-mechanism` is a routing directive, not a priority override.

## Bearer transport conflicts with OIDC

The second surprise: declaring `getCredentialTransport()` with `Type.AUTHORIZATION, "bearer"` — even with a different `authenticationScheme` — creates transport-type ambiguity with the OIDC mechanism. Both declare Bearer. Quarkus can't distinguish them for challenge sending. The OIDC mechanism's challenge flow fires for requests it shouldn't handle, connecting to a non-existent OIDC server.

The Javadoc explicitly warns about this: "using multiple HTTP authentication mechanisms to use the same credential transport type can lead to unexpected authentication failures." I read that sentence three times during implementation. It landed differently each time.

## The production-only regression

The third surprise was the most subtle. Claude caught it during spec review — a production-only `MissingTenancyException` that no test could surface.

The chain: `PluginTokenBridgeMechanism` creates a non-anonymous `SecurityIdentity`. In production, `OidcCurrentPrincipal @Priority(100)` wins CDI resolution for `CurrentPrincipal`. Its `tenancyId()` method checks `identity.isAnonymous()` → false (mechanism authenticated) → falls through to `jwt.claim("tenancyId")` → no JWT exists → throws. Two of three plugin endpoints hit store queries that call `tenancyId()`.

Tests never catch this because `FixedCurrentPrincipal @Priority(200)` wins over `OidcCurrentPrincipal` in every test class. The JWT path never executes in any test environment. The regression manifests only in production.

The fix is `OpenClawCurrentPrincipal @Alternative @Priority(150)` — an app-local `CurrentPrincipal` that detects bridge-authenticated identities via a `SecurityIdentity` attribute and returns the default tenancyId. For OIDC paths it delegates to `OidcCurrentPrincipal`. Filed platform#121 for the long-term fix (make `OidcCurrentPrincipal` check SecurityIdentity attributes before falling through to JWT).

## What actually works

The mechanism that survived: path guard in `authenticate()` (returns null for non-plugin paths), null from `getCredentialTransport()` ("this mechanism cannot interfere with other mechanisms"), and never throwing `AuthenticationFailedException` (any thrown exception triggers OIDC challenge flow → 500). `@RolesAllowed` on the resource handles authorization. Proactive auth (Quarkus default) invokes the mechanism. `@TestSecurity` intercepts at higher priority because no `auth-mechanism` routing overrides it.

It's less elegant than the spec envisioned — path checking in the mechanism instead of framework-managed routing — but it's the only combination that works with OIDC coexistence, `@TestSecurity`, and the challenge flow.

The Quarkus auth mechanism API is designed for the single-mechanism case. The moment you have two mechanisms reading Bearer tokens on different paths, every framework convenience becomes a trap.
