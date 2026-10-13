---
layout: post
title: "Seven rounds to get a spec right — and what that means"
date: 2026-06-20
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-openclaw]
series: issue-31-extract-oversight-gate-service
---

The plan for #31 was straightforward enough: move `OversightGateService` out of casehub-openclaw and into casehub-engine-api where it belongs, classified as a Known Placement Violation in PLATFORM.md. I assumed it would be a spec → plan → implement cycle of maybe two or three review passes. It took seven.

That's not a complaint. Every iteration caught something real.

The first few iterations were structural — which types belong in the SPI (`GateDecision`, the two interface variants), which stay as implementation details (`GateContext`, `OversightGateDispatcher`), whether `evaluate()` belongs in the contract at all. It doesn't: it's a webhook-archiving concern specific to how OpenClaw delivers agent output. Putting it on a platform SPI would mean every future harness gets a method with no valid implementation.

The classification delegation issue came up on iteration two and was the most architecturally interesting catch. The current `OversightGateService.classifyMostRestrictive()` is a private re-implementation of `ChainedReactiveActionRiskClassifier` — except it only handles blocking `ActionRiskClassifier` beans. Any `ReactiveActionRiskClassifier` registered in the deployment is silently ignored. The fix is obvious once you see it: delete the three private methods and delegate to the injected `ReactiveActionRiskClassifier`. But there's a catch: `ChainedReactiveActionRiskClassifier` lives in engine runtime, not engine-api, and casehub-openclaw deliberately avoids engine runtime because engine's CDI beans have unsatisfied persistence SPIs. So we needed the class moved to engine-api first — into a new `io.casehub.api.classification` package, explicitly the first concrete CDI bean in the api module.

The reactive variant decision came up next. The platform pattern is `ReactiveXxx` alongside `Xxx` for operational SPIs, and I was inclined to follow it. The reviewer pointed out that `ReactiveOpenClawOversightGateService` shouldn't follow the split-class pattern of `ReactiveOpenClawWorkerProvisioner` — that class re-implements everything because it's all in-memory and can be. A full reactive re-implementation of the oversight gate would require `@IfBuildProperty`-gated reactive Qhorus services, coupling the reactive gate variant to the Qhorus reactive build flag. The thin delegate is correct here: `Uni.createFrom().item(() -> delegate.openGate(...)).runSubscriptionOn(Infrastructure.getDefaultWorkerPool())`. The reviewer caught this; I agree.

The `runSubscriptionOn` issue itself came up in a later iteration. We had written `Uni.createFrom().item(Supplier)` and left it without the offload — the Uni looks reactive but the supplier executes synchronously on the subscribing thread. Without `.runSubscriptionOn(Infrastructure.getDefaultWorkerPool())` a Vert.x IO thread subscriber would deadlock or throw. The existing `ChainedReactiveActionRiskClassifier` source already shows the correct pattern, which is convenient confirmation. The reactive interface exists partly for platform parity, but the harder justification is this thread constraint: `openGate()` calls `await().indefinitely()` internally, so any caller on an IO thread must use `ReactiveOversightGateService`.

Several iterations were mechanical: wrong class name in one test table, `git rm` on an untracked file that hadn't been committed yet, a stale comment in `OversightGateDispatcherCdiTest` referencing `CaseChannelNames` after we deleted it, `CommitmentToolsTest` needing two import updates not one. These shouldn't have required iteration — they're the kind of thing a careful read catches the first time.

While the spec was in review, the engine session filed and closed engine#538, implementing Part A. That's when CI broke. The engine had also shipped engine#530 (`ProvisionContext` gained a `String taskType` at position 3), and openclaw's test helpers were calling the old 6-arg constructor. The `NoSuchMethodError` named the old signature, which makes it look like the method was removed rather than extended. We fixed both test helpers with a single `null` each, but the fix was on the feature branch. CI runs against main. The cherry-pick to main was the right call.

The spec is done. Seven iterations is on the high end — this one had both a cross-module dependency (engine-api needs to ship first) and a design correction mid-review (the thin delegate vs split-class question). The design is better for the iteration. openclaw is waiting on the engine snapshot to implement Part B.
