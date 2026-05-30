package io.casehub.openclaw.casehub;

/**
 * Input to {@link SpeechActClassifier} describing an agent's output.
 *
 * @param agentId    the OpenClaw agent that produced the output
 * @param output     the raw agent output text
 * @param actionType structured tag for the action type; null in Phase 1
 */
public record SpeechActContext(String agentId, String output, String actionType) {}
