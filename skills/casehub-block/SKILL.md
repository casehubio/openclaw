---
name: casehub-block
description: Temporarily block a CaseHub commitment pending an external dependency, extending the Watchdog deadline
version: 1.0.0
triggers:
  - "I'm blocked on X"
  - "waiting for X to resolve"
  - "can't proceed until Y"
  - "on hold pending Z"
  - "blocked by X"
tools:
  - casehub_block
  - casehub_checkpoint
permissions: []
---

You are temporarily blocking a tracked CaseHub commitment because an external
dependency prevents progress. This extends the Watchdog deadline so the commitment
does not escalate prematurely.

## Procedure

1. **Confirm you have an active `commitmentId`.** If not, tell the user.

2. **Identify the blocker** — what specifically is preventing progress (`reason`).

3. **Estimate `blockedUntil`** — a future ISO-8601 timestamp. Ensure it is in the future;
   a past timestamp returns `DEADLINE_IN_PAST`.
   - If the resolution time is known → use it.
   - If the blocker is indefinite (you have no idea when it resolves) → **consider
     `casehub_escalate` instead** to hand off to whoever can unblock this.
   - If the window is unknown but finite → extend by a reasonable estimate (1 hour,
     4 hours, 1 day) and explain the estimate to the user.

4. **Call the tool:**

```
casehub_block(
  agentId      = YOUR_AGENT_ID,
  commitmentId = COMMITMENT_ID,
  reason       = BLOCKER_DESCRIPTION,
  blockedUntil = ISO_FUTURE_TIMESTAMP
)
```

5. **Handle errors:**
   - `COMMITMENT_NOT_FOUND`: the commitment is not tracked — it may be from a previous session or already closed. Do not retry; report to the user.
   - `COMMITMENT_ALREADY_CLOSED`: the commitment is already in a terminal state. No block needed.
   - `COMMITMENT_UNAUTHORIZED`: you are not the obligor for this commitment. Cannot extend a deadline you don't own.
   - `DEADLINE_IN_PAST`: the `blockedUntil` timestamp is already past — provide a future timestamp (see step 3 guidance).

6. **Confirm `newWatchdogDeadline`** to the user.

7. **When the blocker resolves:** call
   `casehub_checkpoint(agentId, commitmentId, "UNBLOCKED: <note>")` to resume
   normal monitoring.

## Restart recovery

If the Quarkus service restarts while you are blocked, the in-memory channel binding
for this commitment is lost. Calling `casehub_checkpoint` after a restart will return
`COMMITMENT_NOT_FOUND` — do not retry it.

For channel-backed commitments: calling `casehub_done` after a restart closes the
commitment in the store, but no DONE is dispatched to the work channel. If audit trail
completeness matters, let the Watchdog handle escalation rather than calling
`casehub_done` after a restart.
