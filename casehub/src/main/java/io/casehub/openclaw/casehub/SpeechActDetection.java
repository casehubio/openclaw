package io.casehub.openclaw.casehub;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.casehub.qhorus.api.message.MessageType;

/**
 * Detects explicit speech act signals in agent output text.
 *
 * <p>Tries Tier 1 (JSON) then Tier 2 (bracket prefix). Returns
 * {@code Optional.empty()} when no explicit signal is found — the caller
 * supplies the fallback.
 *
 * <p>JSON parsing uses strict mode ({@code FAIL_ON_TRAILING_TOKENS}) so that
 * output containing JSON followed by explanatory text falls through to Tier 2
 * rather than silently matching.
 *
 * <p><b>Internal API — no stability guarantee.</b> Will be promoted to a
 * stable API when {@code casehub-openclaw-inference} is introduced
 * (openclaw#27).
 */
public final class SpeechActDetection {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private static final Pattern PREFIX_PATTERN =
            Pattern.compile("^\\[([A-Za-z]+)\\]:?\\s*");

    private static final Set<MessageType> CLASSIFIABLE = Set.of(
            MessageType.DONE, MessageType.STATUS, MessageType.DECLINE,
            MessageType.FAILURE, MessageType.RESPONSE);

    private SpeechActDetection() {}

    /**
     * Attempts to detect a speech act in {@code output}.
     * Trims input before matching. Returns {@code Optional.empty()} if no
     * explicit signal is found.
     */
    public static Optional<SpeechActResult> detect(String output) {
        if (output == null || output.isBlank()) {
            return Optional.empty();
        }
        String trimmed = output.trim();

        // Tier 1 — JSON
        if (trimmed.startsWith("{")) {
            Optional<SpeechActResult> json = tryJson(trimmed);
            if (json.isPresent()) return json;
        }

        // Tier 2 — bracket prefix
        return tryPrefix(trimmed);
    }

    @SuppressWarnings("unchecked")
    private static Optional<SpeechActResult> tryJson(String trimmed) {
        try {
            // Use Map to avoid Jackson record deserialization issues with a standalone ObjectMapper.
            // instanceof String guards handle both absent and null content field values.
            Map<String, Object> map = MAPPER.readValue(trimmed, Map.class);
            Object typeObj = map.get("type");
            Object contentObj = map.get("content");
            if (!(typeObj instanceof String typeStr)) return Optional.empty();
            if (!(contentObj instanceof String contentStr)) return Optional.empty();
            MessageType type = parseType(typeStr);
            if (type == null) return Optional.empty();
            return Optional.of(new SpeechActResult(type, contentStr, DetectionTier.JSON));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static Optional<SpeechActResult> tryPrefix(String trimmed) {
        Matcher m = PREFIX_PATTERN.matcher(trimmed);
        if (!m.find()) return Optional.empty();
        MessageType type = parseType(m.group(1));
        if (type == null) return Optional.empty();
        String content = trimmed.substring(m.end());
        return Optional.of(new SpeechActResult(type, content, DetectionTier.PREFIX));
    }

    private static MessageType parseType(String s) {
        if (s == null) return null;
        try {
            MessageType t = MessageType.valueOf(s.toUpperCase());
            return CLASSIFIABLE.contains(t) ? t : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
