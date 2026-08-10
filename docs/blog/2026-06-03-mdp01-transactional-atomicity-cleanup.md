---
layout: post
title: "The @Transactional Catch Block Trap"
date: 2026-06-03
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-openclaw]
tags: [quarkus, cdi, transactions, oversight-gate]
---

The session was backlog cleanup — six small items that had been sitting since Epic 7 closed. Most of them were administrative: close an issue the code had outrun, check an upstream bug's status, file a parent repo issue for a doc update. One was interesting.

I'd flagged #15 at Epic 6 close: `OversightGateService.fulfill()` dispatches two messages in sequence — RESPONSE or DECLINE to the oversight channel (which resolves the Commitment), then STATUS to the work channel. Crash between the two and you end up with a fulfilled Commitment but a work channel that never heard about it. The case step hangs until Watchdog fires.

The obvious fix is `@Transactional` on `fulfill()`. Except `fulfill()` is fail-open — it catches everything, because the resource layer must always return 200 to OpenClaw. Non-200 means OpenClaw retries, and you don't want it retrying a gate endpoint that may have already partially fired.

These two requirements push against each other. I thought about it before implementing.

The problem with `@Transactional` + a catch block is that the catch block doesn't actually give you full fail-open. Here's what happens when a `dispatch()` call throws: `dispatch()` is itself `@Transactional(REQUIRED)`. When it throws a `RuntimeException`, the ArC interceptor marks the *outer* transaction — `fulfill()`'s — as rollback-only, then re-throws into `fulfill()`. The catch block swallows that exception and `fulfill()` returns normally. Then `fulfill()`'s own ArC interceptor tries to commit, finds it rollback-only, and throws `TransactionalException` to `fulfill()`'s caller. The catch block never had a chance at it: the `TransactionalException` fires from the CDI interceptor *after the method body has returned*.

So the stack looks fail-open but isn't. The resource gets a 500.

The fix is to not put `@Transactional` on `fulfill()`. I extracted a package-private `OversightGateDispatcher` bean that owns the two-dispatch pair:

```java
@ApplicationScoped
class OversightGateDispatcher {
    @Inject MessageService messageService;

    @Transactional
    void dispatch(boolean approved, UUID oversightChannelId, UUID workChannelId,
                  long commandMessageId, UUID gateId, String rawOutput) {
        messageService.dispatch(/* RESPONSE or DECLINE on oversight channel */);
        messageService.dispatch(/* STATUS on work channel */);
    }
}
```

`fulfill()` passes primitives only — UUID, boolean, String, long — no JPA entities. If the dispatcher throws, `TransactionalException` propagates into `fulfill()`'s catch block. This time the catch block catches it. `fulfill()` isn't `@Transactional`, so there's no outer transaction to mark rollback-only. The dispatcher's transaction rolled back; the catch block logged and returned; the resource returned 200. Both dispatches committed together or neither did.

We also fixed a smaller thing from the code review. In `openGate()`, the COMMAND dispatched to the oversight channel was using `agentId` as the sender — as if the agent had sent it. It should be `GATE_SENDER` ("openclaw-gate"). The gate is acting on the agent's behalf, not the agent acting for itself. Getting that wrong doesn't break anything functionally but it corrupts the audit trail.

The CDI behaviour above went into the garden: the catch block can't prevent `TransactionalException` from propagating when the transaction is marked rollback-only by an inner bean. Non-obvious enough to be worth recording.
