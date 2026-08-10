---
layout: post
title: "The architecture document that doesn't fit the profile"
date: 2026-06-02
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-openclaw]
tags: [arc42stories, documentation, integration-tier, architecture]
---

The first thing I noticed when starting ARC42STORIES.MD was that LAYER-LOG.md was
completely wrong. Every Epic from 2 through 7 was marked "Pending" or "In progress"
— but all of them had been closed in GitHub weeks ago. The LAYER-LOG never got
updated after the work was done. That's the thing about documentation written
at layer-close time: it's accurate for about a day.

The second thing I had to work out was what kind of document this was supposed to be.

The CaseHub Profile has a layer taxonomy for harness apps: casehub-work,
casehub-qhorus, casehub-ledger, casehub-engine, in sequence. casehub-openclaw
doesn't follow that sequence. It's not a harness app — it doesn't build up
foundation modules one layer at a time. The connector pattern is different:
all foundation SPIs arrive together as `casehub-engine-api` interfaces, and
you wire them up to the runtime in one go.

So the layer taxonomy had to be the module tiers themselves: L1 `core/` (hook
client, ChannelContextWindow), L2 `casehub/` (SPI implementations), L3 `app/`
(REST, MCP), L4 `python/` (SDK), L5 `plugin/` (TypeScript hooks), L6 `skills/`
(SKILL.md files). Six layers, each a different language or runtime concern. The
CaseHub Profile notes that integration-tier components aren't listed — it covers
application tier and foundation tier. Openclaw is neither.

Before writing anything, Claude and I read everything: all six blog entries from
Epics 2–7, both ADRs, the arc42stories spec, the CaseHub Profile, the clinical
ARC42STORIES.MD as reference, and the source tree to verify every class name. The
source tree check mattered. The LAYER-LOG and design specs name classes by concept
and intention. Production code names them by what was actually built. Those don't
always match. In the devtown migration that had gone before us, 10+ documentation
errors were caught by systematically running `find` on every class listed in Key
files sections. We ran the same checks.

Two of the errors we caught weren't class names — they were issue references. The
HANDOFF from the last session listed `parent#118` and `parent#129` as open Technical
Debt items. Both had been closed. HANDOFF had stale cross-repo state; the quality
sweep found it before the document was committed.

The other thing worth noting: Epic 3's GitHub issue (#3) is still open, despite
the ChannelContextWindow code being complete since May 28th. The Flyway migration
was dropped from scope early on — the ring buffer is in-memory by design — but
nobody closed the issue after that decision. It's flagged in §12 for closure.

The document covers §1 through §13. Each section has a declared mode: reference/lookup
for §1–3, explanation/comparative for §4, how-to/diagnostic for anti-patterns and
gotchas, tutorial for pattern-to-replicate sections, argumentation for ADRs. The
anti-patterns in §8 came directly from the blog entries — six entries across six
epics, each with at least one non-obvious failure mode. The Python hook that doesn't
exist. The hook registration timing window. The `allowConversationAccess: true` silent
drop. The casehub-engine vs casehub-engine-api CDI explosion. The ChannelBackend.post()
throw that aborts fanOut silently.

casehub-openclaw now has the same documentation foundation as devtown and clinical.
The prefix is `OC`. The document lives at the project root. The LAYER-LOG points to it.
