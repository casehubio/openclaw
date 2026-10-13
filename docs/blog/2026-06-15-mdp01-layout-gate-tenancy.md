---
layout: post
title: "Removing AgentKey and making the context window self-resolving"
date: 2026-06-15
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-openclaw]
tags: [multi-tenancy, agentkey, context-window, refactoring]
---

The last entry added `AgentKey(agentId, tenancyId)` to `ChannelContextWindowService` to prevent same-agentId cross-tenant collision. Sound reasoning at the time. Except `OpenClawAgentRegistry` — the other map that tracks agent→case associations — uses a plain String key. Always has. The inconsistency sat there until this branch surfaced it.

When `workerId` eventually replaces `agentId` as the stable routing key (ARC42 §2 documents this as the planned fix for concurrent same-agent workers), both maps need to rekey. `AgentKey(agentId, tenancyId)` buys nothing toward that migration — the future key is `workerId`, not a compound of `agentId` and `tenancyId`. We removed `AgentKey` and made the service consistent with the registry: same plain-String key, same last-write-wins semantics, same refactor point when `workerId` arrives.

Removing it also resolved the original problem differently. #33 was filed as "tenancyId propagation for TypeScript plugin and Python SDK" — the `GET /channel-context/{agentId}` endpoint was returning the wrong tenant's window because `MockCurrentPrincipal` always resolves to `DEFAULT_TENANT_ID` in plugin/SDK context. Three options came up in design:

(A) Auth retrofit — wait for OIDC to wire properly, plugin requests carry a real principal. Upstream dependency, no timeline.

(B) Pass tenancyId through OpenClaw's session context at provision time, have the plugin send it as a header. Requires OpenClaw to expose session config to hooks, unknown whether it does.

(C) Make `ChannelContextWindowService` self-resolving — at `bindAgent(agentId, caseId)` time, record which tenancyId this agent belongs to internally; `query(agentId, since)` resolves it from that map. No principal needed, plugin/SDK need no changes.

Option C turns out to be the right answer for a reason that only becomes obvious when you ask why the resource needed a principal in the first place: it didn't. The service already knew the tenancyId from the provisioning call. The resource was doing extra work to resolve something it didn't need to resolve. Once `AgentKey` was gone, the tenancyId-to-agentId mapping was already implicit in the plain-agentId lookup — the service became fully self-contained.

The gate recovery (#34) was simpler. Pre-#29 gate COMMAND messages don't have a `tenancyId` key in their `GateContext` content — `parseGateContent()` returns `Optional.of(GateContext(oci, wci, cmi, null))`. That null tenancyId reaches `gateDispatcher.dispatch()`, which falls back to `DEFAULT_TENANT_ID`. For non-default tenants, every dispatch in `fulfill()` lands in the wrong message store. The fix: `fulfill()` already holds `UUID oversightChannelId = gateCmd.channelId`. The oversight channel knows its tenancyId; Qhorus wrote it at creation time. `CrossTenantChannelStore.findById(oversightChannelId)` exists in the Qhorus jar and returns a complete `Channel` entity. Three lines added, correct behaviour for any pre-#29 gate that arrives, no migration note needed.

The layout extraction (#32) was mechanical — both `CaseChannelProvider` implementations had identical private `ChannelSpec` records and `LAYOUT` maps — but the consolidation surfaced a latent improvement: `ChannelSpec.allowedTypes` and `deniedTypes` are now `Set<MessageType>` rather than `String`. Both providers had been parsing strings with `MessageType.valueOf()` at every `openChannel()` call. The new single source of truth eliminates the parsing, the duplication, and any risk of the two providers quietly diverging.

One thing this implementation surfaced: when writing a plan that enumerates callers of a method about to change signature, `ide_find_references` on the definition site is the only reliable source. Manual inspection missed `CasehubMcpResources` — which also queries the channel context window — and the compile break arrived mid-task rather than pre-task where it would have cost nothing to catch.
