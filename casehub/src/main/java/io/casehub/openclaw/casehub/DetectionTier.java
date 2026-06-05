package io.casehub.openclaw.casehub;

/**
 * Indicates which detection tier produced a {@link SpeechActResult}.
 * JSON and PREFIX represent explicit agent signals; FALLBACK means no
 * explicit signal was present. Future NliSpeechActClassifier adds NEURAL.
 */
public enum DetectionTier {
    JSON,
    PREFIX,
    FALLBACK
}
