package io.casehub.openclaw.casehub;

import io.casehub.qhorus.api.message.MessageType;

/**
 * Classifies an OpenClaw agent output into a Qhorus {@link MessageType}.
 *
 * <p><b>Phase 1 ({@link DefaultSpeechActClassifier}):</b> always returns
 * {@link MessageType#DONE}. Inferred from invocation context — a COMMAND was
 * received and this is the agent's completion response.
 *
 * <p><b>Phase 2 (openclaw#10):</b> detect skill-output prefix conventions prepended
 * by SKILL.md instructions — e.g. "[STATUS] Boiler pressure 1.2 bar" → STATUS.
 *
 * <p><b>Phase 3 (openclaw#10):</b> parse structured JSON output from skills that
 * provide machine-readable speech acts: {@code {"type":"STATUS","content":"..."}}.
 *
 * <p>This interface exists now to isolate classification from {@link OversightGateService}.
 * Phase 2/3 implementations are drop-in replacements. Override with
 * {@code @Alternative @Priority(1)}.
 */
public interface SpeechActClassifier {
    MessageType classify(SpeechActContext context);
}
