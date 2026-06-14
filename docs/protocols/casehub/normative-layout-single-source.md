---
id: PP-20260615-11b9d2
title: "All changes to the normative channel layout must go through OpenClawNormativeLayout"
type: rule
scope: repo
applies_to: "casehub-openclaw — any code in casehub/ that creates or configures Qhorus channels per case"
severity: important
refs:
  - docs/specs/openclaw-integration.md
violation_hint: "A private ChannelSpec record or LAYOUT map defined inside OpenClawCaseChannelProvider or ReactiveOpenClawCaseChannelProvider — bypasses OpenClawNormativeLayoutTest value assertions and re-introduces the duplication eliminated in #32"
garden_ref: GE-20260614-3205f6
created: 2026-06-15
---

`OpenClawNormativeLayout` (package-private, `casehub/`) is the single source of truth for the three normative channels (work/observe/oversight) and their MessageType constraints. `OpenClawNormativeLayoutTest` asserts the exact `allowedTypes`/`deniedTypes` values per channel — any provider that defines its own private layout map bypasses this guard entirely. When the normative layout changes (e.g. a fourth channel type is added — tracked in openclaw#32), update `OpenClawNormativeLayout.LAYOUT` only; both `OpenClawCaseChannelProvider` and `ReactiveOpenClawCaseChannelProvider` consume it directly and will pick up the change automatically.
