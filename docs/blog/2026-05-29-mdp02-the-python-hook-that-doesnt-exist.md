---
layout: post
title: "The Python hook that doesn't exist"
date: 2026-05-29
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-openclaw]
tags: [openclaw, typescript, python, hooks, before_prompt_build]
---

The research spec had `@agent.on("before_prompt_build")` in Python. That code doesn't run anywhere. OpenClaw's hook system is TypeScript only.

I found this out at the start of Epic 5. Before writing any implementation, Claude and I tried to verify the exact hook API — the official docs at docs.openclaw.ai returned 403, so we searched sideways: GitHub issues, third-party plugins, npm packages. Every plugin implementing `before_prompt_build` is 100% TypeScript. The Python App SDK manages agents via HTTP, handles cron jobs, builds pipelines. No in-process hook registration exists. The pseudocode in the research spec was illustrative, not literal.

This changed the plan entirely.

## One job each

The TypeScript plugin owns the full in-process hook lifecycle: registers `before_prompt_build` synchronously in `register(api)`, manages the cursor state, formats the injection. The Python package became what it should have been from the start — a typed HTTP client for Python skill scripts that want to query channel context explicitly.

```typescript
export class ChannelContextPlugin {
  register(api: OpenClawPluginApi): void {
    // Synchronous — OpenClaw snapshots hooks at registration time,
    // before the async start() phase. Hooks wired in start() are silently dropped.
    api.on("before_prompt_build", (ctx) => this._inject(ctx));
  }
}
```

Two non-obvious constraints on that registration. First, it must be synchronous — OpenClaw snapshots the hook registry at plugin load time, roughly 30 seconds before `start()` runs. Second: since 2026.4.23, users must add `hooks.allowConversationAccess: true` to their OpenClaw config entry or the registration is silently dropped a second time.

Both are silent failures. No error. The handler just never fires.

## The cursor that doesn't leak

The plugin tracks an internal cursor — the `windowSeq` from the last ChannelContextWindow response — to avoid re-delivering already-seen messages. The obvious key for that cursor Map is `${agentId}:${sessionKey}`. OpenClaw sessions reset daily, so the Map grows indefinitely as session IDs accumulate.

Fix: key by `agentId` only, store the `sessionKey` alongside.

```typescript
private readonly cursors = new Map<string, { cursor: number; sessionKey: string }>();
```

When the sessionKey changes (new session), reset the cursor to zero — the new session gets the full buffered window. The Map stays bounded by distinct agents regardless of how many sessions they've accumulated.

## Five cases, one order

The injection algorithm is five cases applied in order: network failure → fail open. Agent not wired to any Qhorus channels → skip silently. Service restart detected via cursor drift → reset and skip this turn. Ring buffer overflow → inject overflow notice. Messages present → inject formatted messages. No messages, no overflow → inject idle notice with elapsed time since last activity.

The overflow and messages cases are additive. When overflow occurred but newer messages still exist, inject both. First drafts treated them as if/else.

## Three things we didn't catch the first time

`plugin/node_modules` and `plugin/dist` were fully committed — 1,243 generated files — because the project root `.gitignore` doesn't cover subdirectories added after the fact, and `plugin/` had no `.gitignore` of its own. Claude spotted this in the final diff. The fix was a one-line file and `git rm --cached`. There was also a missing Python timeout test (the spec listed it as case 8; the implementation skipped it) and a redundant type cast — `result.messages as ContextMessage[]` where the `WindowContent` interface already makes `messages: ContextMessage[]` explicit.

The plugin is built: 22 TypeScript tests, 10 Python tests. The `allowConversationAccess` constraint is the one thing every future integrator needs to know about — it's in the README and the protocol index now.
