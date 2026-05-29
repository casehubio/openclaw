# 0001 — OpenClaw Hook Implementation Language

Date: 2026-05-29
Status: Accepted

## Context and Problem Statement

casehub-openclaw requires a `before_prompt_build` hook to inject recent Qhorus channel
activity into OpenClaw agent system prompts before each turn. The project's primary languages
are Java (Quarkus) and Python, making a Python implementation the natural first choice.
The research spec pseudocode showed `@agent.on("before_prompt_build")` in Python.

## Decision Drivers

* The hook must fire in-process, before each OpenClaw agent turn
* The Python package (`python/`) must remain a pure client library for PyPI distribution
* The implementation must be documented, supportable, and follow OpenClaw's native extension model

## Considered Options

* **Option A: TypeScript Plugin SDK** — implement as a proper OpenClaw in-process plugin
* **Option B: Python App SDK** — implement via the Python App SDK (`from openclaw import OpenClawClient`)
* **Option C: Python subprocess bridged from TypeScript** — TypeScript plugin shells out to Python

## Decision Outcome

Chosen option: **Option A (TypeScript Plugin SDK)**, because the Python App SDK has no hook
registration mechanism — `agent.on()` does not exist in that SDK. This is a constraint, not
a preference.

### Positive Consequences

* Hook follows OpenClaw's native plugin model — correct, documented, supported
* Python package stays focused as a clean HTTP client library for skill scripts
* Clear architectural boundary: TypeScript handles in-process lifecycle; Python handles external queries

### Negative Consequences / Tradeoffs

* Adds TypeScript as a third language to the repo (alongside Java and Python)
* TypeScript toolchain (`npm`, Vitest) must be maintained independently from Maven
* Plugin consumers must configure `hooks.allowConversationAccess: true` in their OpenClaw config

## Pros and Cons of the Options

### Option A — TypeScript Plugin SDK

* ✅ Only supported mechanism for `before_prompt_build` hooks
* ✅ All real-world OpenClaw plugins use this path
* ✅ `ctx.agentId`, `ctx.sessionKey` available in hook context (confirmed via OpenClaw issue #52411)
* ❌ Third language in repo

### Option B — Python App SDK

* ✅ Consistent language with existing Python package
* ❌ `agent.on()` does not exist — the Python App SDK is a REST API wrapper with no hook registration
* ❌ Would silently produce code that cannot work

### Option C — Python subprocess bridged from TypeScript

* ✅ Keeps hook logic in Python
* ❌ No architectural benefit — TypeScript can make HTTP calls and format strings natively
* ❌ Adds fragile subprocess IPC with no gain

## Links

* Research finding: `docs/specs/2026-05-29-epic5-python-sdk-design.md` §2 (Key Research Finding)
* Protocol: `PP-20260529-7f6b73` — openclaw-hook-typescript-only
* Issue: casehubio/openclaw#5
