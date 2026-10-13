---
layout: post
title: "What a quick import fix revealed"
date: 2026-06-23
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-openclaw]
tags: [casehub, openclaw, api-migration, engine-api]
---

The plan was simple: fix a broken import (PlannedAction moved packages), close a test-polish ticket, and be done. Neither issue was hard. I had a branch in under ten minutes.

Before starting the actual work, I stopped to look at #31 — extracting OversightGateService to casehub-engine-api. It's been parked on the pause stack since June and the issue described it as an L/High extraction job. I'd been treating it as "big work, later."

Turned out the interface was already in engine-api. `io.casehub.api.spi.OversightGateService`, `GateDecision` sealed type right alongside it — exactly what #31 said needed to be created. The heavy lift had already been done, probably as part of engine's own cleanup. What remained was just wiring openclaw's concrete class to implement the existing interface and dropping the local duplicate. Rescoped it on GitHub: S/Low, not L/High.

While looking at the engine-api types, I noticed something about the naming. Both `RiskDecision` and `GateDecision` have an `Autonomous` permit variant — same word, two different layers, subtly different meanings. `RiskDecision.Autonomous` means the classifier decided no gate is needed. `GateDecision.Autonomous` means the gate tried to open and let the action through anyway — a fail-open result, not a policy decision. The parallel naming implies a symmetry that doesn't exist. Filed engine#563 to rename `GateDecision` → `GateOutcome`.

Back to the actual work. The import fix itself compiled immediately — `io.casehub.worker.api.PlannedAction` was the right package. But PlannedAction itself had been restructured. The old record carried identity fields: `agentId`, `caseId`, `tenancyId`. The new one is just `(description, actionType, parameters)`. Identity moved to a separate `ClassificationContext` record that's now the second argument to `ActionRiskClassifier.classify()`.

So the import line was one line. The adaptation was not. `OversightGateService.classifyMostRestrictive()` needed a `ClassificationContext` built from the method parameters and threaded through. `DemoGateClassifier.classify()` needed to read `workerId` from the context instead of the action. The tests needed rewriting because the Mockito stubs were stubbing the old one-arg `classify()`. And `casehub-worker-api` needed adding as an explicit compile dependency — the package move put PlannedAction in a separate jar from engine-api, and Maven won't give you transitive access to a jar that the declared dependency doesn't itself depend on.

The `casehub-worker-api` dep had one wrinkle. The locally installed engine-api jar in `~/.m2` still had the old API. The local engine source had been updated but not installed. Main compilation succeeded (the test environment resolved something differently), test compilation didn't. The disorienting part: the error said "required: `io.casehub.api.spi.PlannedAction`" with one arg, but our stubs were passing two. Stared at that for a minute before realising the cached jar was the culprit. `mvn install` on the engine locally, everything cleared.

Claude also ran into a different problem. I asked it to fix the import line in `OversightGateService.java` using the IntelliJ refactor tool, positioning on the class name in the import statement. The tool followed the symbol reference to the class declaration and renamed the class globally — including the `.java` file. Git showed `R OversightGateService.java -> PlannedAction.java`. The fix was a `git mv` back. Straightforward recovery, but worth recording: `ide_refactor_rename` on an import statement renames the declared symbol, not the import path. For import fixes, use Edit directly. (Submitted to the garden as GE-20260623-b460d4.)

Both #46 and #45 closed in one commit.

The session leaves #31 in better shape than I found it — smaller scope, clear path, waiting on engine#563 before adopting the renamed type. The remaining auth work (#42, #43, #44) is still upstream-blocked. The real finding today was that the engine-api cleanup had already done the work I'd been deferring. Worth checking that assumption before assuming a task is as large as its ticket says.
