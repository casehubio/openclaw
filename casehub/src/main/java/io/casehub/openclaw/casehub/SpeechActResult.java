package io.casehub.openclaw.casehub;

import io.casehub.qhorus.api.message.MessageType;

/**
 * Result of {@link SpeechActClassifier#classify(SpeechActContext)}.
 *
 * @param type    the classified Qhorus {@link MessageType}
 * @param content the stripped message body — bracket prefix and JSON envelope
 *                are removed; never null (empty string when output was null or blank)
 * @param tier    which detection tier produced this result
 */
public record SpeechActResult(MessageType type, String content, DetectionTier tier) {}
