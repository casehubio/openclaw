# SSE reconnection backfill and state-aware duration text

**Date:** 2026-07-22
**Branch:** issue-72-sse-reconnect-duration-text
**Issues:** #72, #71

Two UI bugs found by the adversarial design review of the E2E test spec (#59). Both in the Lit web component demo dashboard.

**SSE reconnection backfill (#72):** The `case-execution-view` and `demo-launcher` components used raw `EventSource` without an `onopen` handler. When the SSE connection dropped and auto-reconnected, the UI never re-fetched state — events during the disconnect were permanently lost. Fix: added `onopen` with a `_sseInitialOpen` flag to skip the initial connection (already handled by `connectedCallback`) and call `loadState()`/`loadScenarios()` on reconnection.

**Duration text (#71):** `case-worker-pipeline` displayed "Completed in X.Xs" for all agent outcomes with a non-null `durationMs`, regardless of actual state. A failed agent showing "Completed in 1.5s" is semantically wrong. Fix: extracted a `_durationText(state, durationMs)` method that returns state-appropriate wording — "Failed after", "Timed out after", "Declined after", "Delegated after", or "Completed in".

Both fixes have E2E coverage: a new reconnection backfill test verifies the snapshot is re-fetched after disconnect/reconnect, and the existing outcome tests now assert the correct duration text wording for all five states.
