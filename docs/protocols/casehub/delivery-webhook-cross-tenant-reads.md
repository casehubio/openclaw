---
id: PP-20260612-520281
title: "Delivery webhook handlers must use cross-tenant stores for all entity reads"
type: rule
scope: repo
applies_to: "casehub-openclaw — any @Path('/openclaw/delivery/*') handler that reads entities from Qhorus"
severity: critical
refs:
  - docs/specs/openclaw-integration.md
violation_hint: "Handler injects ChannelService, MessageService, or CommitmentStore and calls findById(), findByName(), or findByCorrelationId() — these methods filter by CurrentPrincipal.tenancyId(), which is MockCurrentPrincipal (DEFAULT_TENANT_ID) in webhook context"
created: 2026-06-12
---

Delivery webhooks (`POST /openclaw/delivery/channel/{channelId}`, `POST /openclaw/delivery/oversight/{gateId}`) are called by OpenClaw with no casehub authentication. `CurrentPrincipal` resolves to `MockCurrentPrincipal`, which returns `DEFAULT_TENANT_ID`. Any Qhorus service call that reads entities through `CurrentPrincipal`-scoped filtering (e.g. `channelService.findById()`, `messageService.findAllByCorrelationId()`, `commitmentStore.findByCorrelationId()`) will silently return empty for entities belonging to non-default tenants — causing 404 responses or missed dispatches that violate the always-200 protocol. All entity reads in delivery webhook handlers must use the cross-tenant stores (`@CrossTenant CrossTenantChannelStore`, `@CrossTenant CrossTenantMessageStore`, `@CrossTenant CrossTenantCommitmentStore`) which perform UUID-keyed or correlation-keyed lookups without tenancy filtering.
