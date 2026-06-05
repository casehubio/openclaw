package io.casehub.openclaw.casehub;

import io.casehub.qhorus.api.message.MessageType;

/**
 * Classifies an OpenClaw agent output into a Qhorus {@link MessageType} and
 * a stripped content string.
 *
 * <p><b>Phase 1 ({@link DefaultSpeechActClassifier}):</b> STATUS fallback for
 * unrecognised output; DONE/STATUS/DECLINE/FAILURE/RESPONSE for explicit signals.
 *
 * <p><b>Phase 2 (openclaw#10):</b> bracket prefix detection —
 * {@code [STATUS] Boiler pressure 1.2 bar} → STATUS.
 *
 * <p><b>Phase 3 (openclaw#10):</b> structured JSON detection —
 * {@code {"type":"STATUS","content":"..."}} → STATUS.
 *
 * <p>Override with {@code @Alternative @Priority(1)}.
 * Any {@code @Alternative} implementation must be updated to return
 * {@link SpeechActResult} — the return type changed from {@link MessageType}
 * in this pass.
 *
 * <p>Future: {@code NliSpeechActClassifier @Alternative @Priority(1)} in
 * {@code casehub-openclaw-inference} (openclaw#27) will call
 * {@link SpeechActDetection#detect(String)} and fall back to ML classification.
 */
public interface SpeechActClassifier {
    SpeechActResult classify(SpeechActContext context);
}
