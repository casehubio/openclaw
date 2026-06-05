package io.casehub.openclaw.casehub;

/**
 * Input to {@link SpeechActClassifier} describing an agent's output.
 *
 * @param agentId the OpenClaw agent that produced the output
 * @param output  the raw agent output text
 */
public record SpeechActContext(String agentId, String output) {}
