---
layout: post
title: "Building the examples directory"
date: 2026-06-17
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-openclaw]
tags: [examples, quarkus, smallrye-config, tdd]
series: issue-35-examples
---

The spec for this took seven review passes before anyone was happy with it. I'm not complaining — the reviewers were right every time, and working through the `@Blocking` deadlock, the `SESSION__KEY` double-underscore, and the `DemoGateClassifier` keyword-to-agentId redesign before writing a line of Java meant we actually built the right thing. But by the time we sat down to implement, the spec had become more documentation than spec — every failure mode pre-identified, every API call verified against the source.

The implementation went cleanly. Four new classes in `app/example/`, each with a narrow job: `DemoGateClassifier` checks `action.workerId()` against a configured agentId (not keyword matching — the keyword approach would have gated on "hot-patch" in the Investigator's outcome text, breaking the "one gate per demo" promise), `ExamplePoller` wraps a single `@Transactional` JPA read so the polling loop can run without holding a transaction open across the 2-second sleeps, `ExampleSetup` owns channel creation and COMMAND dispatch, and `ExampleController` sequences agents and handles the four terminal states (FULFILLED, DECLINED, DELEGATED, or null-from-timeout).

The one runtime discovery we hadn't pre-empted: SmallRye Config treats `property.name=` (empty value in the properties file) as null for non-`Optional<String>` fields, throwing `SRCFG00040` at startup. The `defaultValue = ""` on the `@ConfigProperty` annotation does not save you — that only fires when the property is absent, not when it's present-but-empty. Two minutes of careful error reading would have found this; we used `Optional<String>` and moved on.

The `@Blocking` annotation on `ExampleController.start()` was in the spec by that point — we'd identified that `quarkus-rest` runs on the event loop by default, and a polling loop would deadlock approve.sh. But seeing it written correctly on the first pass still felt satisfying. The failure mode (server-side deadlock, no error, concurrent request silently queued behind the first) is exactly the kind of thing that takes an hour to diagnose from first principles.

There was a pre-existing issue: `DispatchResult` had grown a new `advisories` field, and five test files were still calling the old constructor. We fixed them. The interesting part isn't the fix — it's that the constructor signature change surfaced as a compile error, not a test failure. Anyone who hadn't recompiled recently would have no idea the tests were broken. That's a silent failure mode worth naming.

The examples directory is complete — three docker-compose stacks, six Python mock servers, agent system-prompt files, scenario and approve scripts. The system-prompt files for agents that don't gate (Signal, Risk, Investigator) include a standing instruction not to use the gate keyword in their outcome text. This is imperfect, but the `DemoGateClassifier` now gates on agentId rather than outcome content, so it doesn't actually matter if the Investigator writes "recommend hot-patch". The fix was architectural, not instructional.
