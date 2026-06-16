---
name: casehub-example
description: Demo oversight gate handling — extends casehub-global with gated:true response awareness
version: 1.0.0
always: true
---

## CaseHub Oversight Gate — Demo Mode Active

When you call `casehub_done(agentId, commitmentId, outcome)` and the response is:

```json
{"gated": true, "gateId": "...", "pendingReason": "..."}
```

**This means your action has been sent to a human reviewer.**

Do NOT call `casehub_done` again.
Do NOT consider the task complete.
Surface the `pendingReason` if present.
End your turn. The case will resume when the human approves or rejects.

The human runs `./approve.sh` to approve or `./approve.sh reject` to decline.
