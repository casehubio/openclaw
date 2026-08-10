---
layout: post
title: "Threading tenancyId through a delivery webhook without a principal"
date: 2026-06-12
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-openclaw]
tags: [multi-tenancy, cdi, quarkus, qhorus]
---

The Qhorus multi-tenancy work landed (#260) and openclaw had zero tenancy awareness. Not a single `tenancyId` reference anywhere in the source. That's not unusual at this stage of integration work, but it meant a full pass through every component that touches agents, channels, and the oversight gate.

Most of it was mechanical. `OpenClawAgentRegistry` got a fourth map — `caseToTenancy: ConcurrentHashMap<UUID, String>` — keyed by caseId (globally unique) so the status listener can recover tenancyId at termination time without a request principal. `ChannelContextWindowService` got a composite `AgentKey(agentId, tenancyId)` replacing the bare `agentId` key, which was the only structural change that could cause actual cross-tenant leakage: same agent name used by two tenants, two context windows that would have collided. Everything else was threading the value through — provisioner reads from `CurrentPrincipal`, captures before the Uni lambda if reactive, passes down.

The interesting problem was the delivery webhooks.

OpenClaw posts agent results to `/openclaw/delivery/channel/{channelId}` with no casehub authentication. `CurrentPrincipal` in that context is `MockCurrentPrincipal`, which returns `DEFAULT_TENANT_ID`. The previous code did `channelService.findById(channelId)` — tenant-scoped, returns empty for non-default tenants, returns 404. That's a protocol violation (delivery endpoints always return 200) AND a data loss for any non-default tenant's agent output.

The fix there was simple once I identified the qhorus infrastructure: `CrossTenantChannelStore.findById(UUID)` is a UUID lookup with no tenancy filter. Get the channel entity, get `channel.tenancyId`, pass it to `evaluate()`. Done. No URL changes, no CDI tricks.

The oversight gate path was harder. `OversightGateService.fulfill()` originally bootstrapped via `commitmentStore.findByCorrelationId(gateId)` — also tenant-scoped. With no request principal, that returns nothing. The question was: where does tenancyId come from when the gate callback arrives?

Three options: (A) in-memory map of `gateId → tenancyId` cleared on restart (not crash-safe), (B) embed tenancyId in the callback URL path with a CDI `@RequestScoped` override to scope the qhorus reads (CDI wiring overhead, fragile), (C) persist tenancyId in the `GateContext` serialised into the Qhorus COMMAND message content, then use `CrossTenantMessageStore.scan()` to find the message cross-tenant at fulfillment time.

I went with C. It costs nothing: we already serialise `GateContext` as Properties into the COMMAND content for crash recovery; adding `tenancyId` to that payload is four lines. `fulfill()` now uses `crossTenantMessageStore.scan(MessageQuery.builder().correlationId(gateId.toString()).messageType(MessageType.COMMAND).build())` to find the gate COMMAND regardless of tenant, parses `GateContext` out of the content, gets `tenancyId` from there, and passes it to the dispatcher. No channel entity lookups, no commitment store bootstrap, simpler flow than the original.

The unexpected complication was CDI. The moment we added `@Inject CurrentPrincipal` to `CommitmentTools` and `ChannelContextWindowResource`, Quarkus boot threw `AmbiguousResolutionException`. Two `@DefaultBean @ApplicationScoped` implementations of `CurrentPrincipal` were on the classpath: `MockCurrentPrincipal` from casehub-platform and `QhorusInboundCurrentPrincipal` from casehub-qhorus. Both `@DefaultBean`. Neither wins. The ambiguity had always been there — it just never mattered until a production bean tried to inject the type.

The fix was `quarkus.arc.exclude-types=io.casehub.platform.mock.MockCurrentPrincipal` in production `application.properties` (it's a test stub; `QhorusInboundCurrentPrincipal` is the runtime bean), and `quarkus.arc.selected-alternatives=io.casehub.platform.testing.FixedCurrentPrincipal` in test `application.properties`. `FixedCurrentPrincipal` is `@Alternative @Priority(1)` so it should have won automatically — but when two `@DefaultBean` beans are both visible, Quarkus throws before the alternative resolution applies. The exclude-types approach is cleaner: remove the stub, let the runtime bean win, let the test alternative override.

There was also a `ledger_subject_sequence` table missing from the test H2 schema. JPA `drop-and-create` doesn't create native sequences, and `QhorusSequenceAllocator` needs one. The fix was an `import-qhorus.sql` with `CREATE SEQUENCE IF NOT EXISTS ledger_subject_sequence START WITH 1 INCREMENT BY 50`, wired via `quarkus.hibernate-orm.qhorus.sql-load-script`. Not documented anywhere — found it by tracing the `QhorusSequenceAllocator` source.

278 tests green across core, casehub, and app. The multi-tenant isolation test (`CrossTenantContextIsolationTest`) confirms the AgentKey fix actually works: bind "bot" to tenant-A and tenant-B, unbind tenant-A, tenant-B window is still there.

The protocol I'd add from this: any endpoint that receives callbacks from external systems without authentication must use cross-tenant stores for all entity reads. `CurrentPrincipal` is worthless in that context. The delivery webhook design had always assumed a request principal existed; this was the first session where that assumption had real consequences.
