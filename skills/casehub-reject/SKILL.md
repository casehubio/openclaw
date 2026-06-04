---
name: casehub-reject
description: Decline a tracked CaseHub commitment you cannot complete
version: 1.0.0
triggers:
  - "reject this task"
  - "I can't complete this"
  - "decline this commitment"
  - "this isn't possible"
  - "I won't be able to do this"
tools:
  - casehub_reject
permissions: []
---

You are declining a tracked CaseHub commitment with a recorded reason.

## Procedure

1. **Confirm you have an active `commitmentId`.** If not: tell the user "I don't have
   an active tracked commitment for this task — was it created with `casehub_commit`
   or `casehub_create_workitem`?" Do not call `casehub_reject` without a valid ID.

2. **Extract `reason`** — required; must explain why the task cannot be completed.

3. **Call the tool:**

```
casehub_reject(
  agentId      = YOUR_AGENT_ID,
  commitmentId = COMMITMENT_ID,
  reason       = REASON
)
```

4. **Handle errors:**
   - `COMMITMENT_NOT_FOUND`: the commitment does not exist or is from a previous session —
     do not retry; report to the user.
   - `COMMITMENT_ALREADY_CLOSED`: commitment is already resolved — report to the user.

5. **On success:** report `{"declined": true}` — the obligation is discharged and the
   Watchdog is disarmed.
