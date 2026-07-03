package io.casehub.openclaw.casehub.scenario;

/**
 * Listener for scenario state changes broadcast as typed events.
 * <p>
 * Implementations must be thread-safe — the store may call {@code onEvent}
 * from multiple threads concurrently.
 * <p>
 * Exceptions thrown by listeners are caught and logged by the store; they do not
 * propagate to callers or prevent other listeners from receiving the event.
 */
@FunctionalInterface
public interface ScenarioEventListener {
    /**
     * Receives a typed case execution event representing a state change.
     *
     * @param event typed event (SCENARIO_STARTED, AGENT_COMPLETED, CHANNEL_MESSAGE, etc.)
     */
    void onEvent(CaseExecutionEvent event);
}
