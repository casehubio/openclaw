---
layout: post
title: "Auth retrofit for casehub-openclaw: when @PermitAll is the right answer"
date: 2026-06-23
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-openclaw]
tags: [quarkus, oidc, cdi, security]
series: issue-41-wire-oidc
---

casehub-life got its auth wiring three days ago (life#40). Now it was openclaw's turn. The task looked the same — add `casehub-platform-oidc`, configure OIDC, annotate REST resources. But openclaw is a different kind of thing.

casehub-life has user-facing endpoints. Life tasks, cases, oversight gates — all called by humans with OIDC tokens. The annotation decision there was straightforward: `@RolesAllowed` on everything, differentiated by household group.

casehub-openclaw is an integration tier. Four of its five REST resources are called by machines: OpenClaw's webhook engine, the Python SDK's `before_prompt_build` hook, the TypeScript plugin. None of them carry a casehub OIDC token. Not because we haven't wired it up yet — because there is no user-facing principal at those call sites. OpenClaw is an external system calling back into casehub, not a user authenticating in.

I spent some time with the spec working out what this meant. The `@PermitAll` annotation isn't a shortcut or an "I'll fix it later" decision. It's the correct description of the architectural reality. When a webhook callback arrives at `/openclaw/delivery/channel/{id}`, there is no token to validate. The tenancy is derived from the channel entity via `CrossTenantChannelStore` — a pattern established in openclaw#29 — not from a bearer token. Annotating that endpoint with `@RolesAllowed` would break OpenClaw callbacks entirely.

So the annotation sweep was four `@PermitAll` at class level and one `@RolesAllowed(OpenClawGroups.ADMIN)` on `ExampleController`, which is the only endpoint a human operator actually triggers. Explicit intent matters here — with `casehub-platform-oidc` active, Quarkus security is globally enabled. Unannotated endpoints stay accessible by default, but the default is fragile. `@PermitAll` on the system-to-system resources makes the decision auditable.

The CDI exclusion was trickier than it needed to be. Adding `casehub-platform-oidc` to the compile classpath puts two unqualified `CurrentPrincipal` implementations in the CDI context: `OidcCurrentPrincipal @RequestScoped` from the new dep, and `QhorusInboundCurrentPrincipal @ApplicationScoped` from casehub-qhorus. Two unqualified beans for the same type with no `@Alternative` or `@Priority` disambiguation is `AmbiguousResolutionException` at augmentation. The fix is adding `QhorusInboundCurrentPrincipal` to `quarkus.arc.exclude-types`.

What slowed that down was the Javadoc. `QhorusInboundCurrentPrincipal`'s Javadoc confidently states that `OidcCurrentPrincipal` has `@Priority(100)` and auto-displaces it when the oidc module is on the classpath. That annotation doesn't exist in the source. The Javadoc describes a design intent that was never implemented. I only found this by decompiling the bytecode. The casehub-life reference implementation excludes `QhorusInboundCurrentPrincipal` in its production properties — that's how I knew the right fix — but without that reference, the Javadoc would have led me to conclude the problem auto-resolved.

I filed qhorus#301 to update the Javadoc. Without a correction it will mislead the next person doing this work in any other casehub repo that adds casehub-platform-oidc.

The `TenancyConstants.DEFAULT_TENANT_ID` finding was a minor satisfaction: it's `"278776f9-e1b0-46fb-9032-8bddebdcf9ce"`, which is exactly the UUID `QhorusInboundCurrentPrincipal` returns from its `ContextNotActiveException` catch block. The sentinel values are aligned. Switching from one principal to the other changes nothing for anonymous paths — the tenant resolution for those endpoints goes through the channel entity anyway.

Three deferred issues came out of the design: `@PermitAll` on the plugin endpoints is an interim decision (openclaw#42); the MCP endpoint needs its own auth mechanism (openclaw#43); delivery endpoint signature validation waits on OpenClaw supporting it (openclaw#44). These are real gaps, not forgotten — just gated on things outside the repo boundary.
