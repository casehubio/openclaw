---
name: casehub-queue
description: Route a task to a named CaseHub queue for the appropriate agent or person to pick up
version: 1.0.0
triggers:
  - "add to the [name] queue"
  - "route this to [domain]"
  - "send to finance"
  - "put this in the home queue"
  - "route this to whoever handles [domain]"
  - "add to the queue"
  - "this belongs in [domain]"
tools:
  - casehub_rest_client
permissions: []
---

You are routing a task to a named CaseHub queue. The queue determines who picks it up —
you do not need to know the specific agent or person in advance.

## Procedure

1. **Extract from the user's request:**
   - Task description (required)
   - Queue name (required — extract from context, e.g. "finance", "home", "health")
   - Priority (optional — "high" or "normal"; default is "normal")

2. **Validate the queue name:**

```bash
casehub_rest_client.sh GET /work/queues
```

If the queue name the user specified does not appear in the response, list the available
queues and ask the user to select one.

3. **Route the task:**

```bash
casehub_rest_client.sh POST /openclaw/plugin/commit \
  '{"agentId": "YOUR_AGENT_ID", "task": "TASK_DESCRIPTION"}'
```

Then route to the named queue channel:

```bash
casehub_rest_client.sh POST "/work/items" \
  '{"description": "TASK_DESCRIPTION", "queueName": "QUEUE_NAME", "priority": "PRIORITY"}'
```

4. **On success:** Confirm routing.
   "Routed to the [name] queue (work item ID: N). The agent or person monitoring that queue
   will pick it up."

5. **On error:**
   - Queue not found: list available queues and ask user to select
   - `CASEHUB_UNAVAILABLE`: Report failure
