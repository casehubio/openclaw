package io.casehub.openclaw.casehub;

import org.junit.jupiter.api.Test;
import io.casehub.qhorus.api.message.MessageType;
import static org.assertj.core.api.Assertions.assertThat;

class SpeechActDetectionTest {

    // ── Tier 1: JSON ──────────────────────────────────────────────────────────

    @Test
    void detect_jsonDone_returnsJsonResult() {
        var result = SpeechActDetection.detect("{\"type\":\"DONE\",\"content\":\"ok\"}");
        assertThat(result).isPresent();
        assertThat(result.get().type()).isEqualTo(MessageType.DONE);
        assertThat(result.get().content()).isEqualTo("ok");
        assertThat(result.get().tier()).isEqualTo(DetectionTier.JSON);
    }

    @Test
    void detect_jsonStatusLowercase_normalises() {
        var result = SpeechActDetection.detect("{\"type\":\"status\",\"content\":\"working\"}");
        assertThat(result).isPresent();
        assertThat(result.get().type()).isEqualTo(MessageType.STATUS);
        assertThat(result.get().content()).isEqualTo("working");
    }

    @Test
    void detect_jsonDecline_returnsDecline() {
        var result = SpeechActDetection.detect("{\"type\":\"DECLINE\",\"content\":\"can't do it\"}");
        assertThat(result).isPresent();
        assertThat(result.get().type()).isEqualTo(MessageType.DECLINE);
        assertThat(result.get().content()).isEqualTo("can't do it");
    }

    @Test
    void detect_jsonFailure_returnsFailure() {
        var result = SpeechActDetection.detect("{\"type\":\"FAILURE\",\"content\":\"error\"}");
        assertThat(result).isPresent();
        assertThat(result.get().type()).isEqualTo(MessageType.FAILURE);
    }

    @Test
    void detect_jsonResponse_returnsResponse() {
        var result = SpeechActDetection.detect("{\"type\":\"RESPONSE\",\"content\":\"answer\"}");
        assertThat(result).isPresent();
        assertThat(result.get().type()).isEqualTo(MessageType.RESPONSE);
    }

    @Test
    void detect_jsonUnknownType_returnsEmpty() {
        var result = SpeechActDetection.detect("{\"type\":\"ESCALATE\",\"content\":\"x\"}");
        assertThat(result).isEmpty();
    }

    @Test
    void detect_jsonNonClassifiableType_returnsEmpty() {
        // COMMAND is a valid MessageType but not classifiable from webhook output
        var result = SpeechActDetection.detect("{\"type\":\"COMMAND\",\"content\":\"x\"}");
        assertThat(result).isEmpty();
    }

    @Test
    void detect_jsonMissingContent_returnsEmpty() {
        var result = SpeechActDetection.detect("{\"type\":\"DONE\"}");
        assertThat(result).isEmpty();
    }

    @Test
    void detect_jsonNullContent_returnsEmpty() {
        var result = SpeechActDetection.detect("{\"type\":\"DONE\",\"content\":null}");
        assertThat(result).isEmpty();
    }

    @Test
    void detect_jsonMalformed_returnsEmpty() {
        var result = SpeechActDetection.detect("{broken");
        assertThat(result).isEmpty();
    }

    @Test
    void detect_jsonFenced_returnsEmpty() {
        var result = SpeechActDetection.detect("```json\n{\"type\":\"DONE\",\"content\":\"x\"}");
        assertThat(result).isEmpty();
    }

    @Test
    void detect_jsonTrailingText_returnsEmpty() {
        // Strict parsing: trailing non-JSON text is a parse failure → fall through
        var result = SpeechActDetection.detect("{\"type\":\"DONE\",\"content\":\"ok\"} extra text here");
        assertThat(result).isEmpty();
    }

    @Test
    void detect_jsonLeadingWhitespace_trimsAndDetects() {
        var result = SpeechActDetection.detect("  {\"type\":\"DONE\",\"content\":\"ok\"}");
        assertThat(result).isPresent();
        assertThat(result.get().content()).isEqualTo("ok");
    }

    // ── Tier 2: Prefix ────────────────────────────────────────────────────────

    @Test
    void detect_prefixDone_returnsPrefixResult() {
        var result = SpeechActDetection.detect("[DONE] task finished");
        assertThat(result).isPresent();
        assertThat(result.get().type()).isEqualTo(MessageType.DONE);
        assertThat(result.get().content()).isEqualTo("task finished");
        assertThat(result.get().tier()).isEqualTo(DetectionTier.PREFIX);
    }

    @Test
    void detect_prefixStatusLowercase_normalises() {
        var result = SpeechActDetection.detect("[status] still running");
        assertThat(result).isPresent();
        assertThat(result.get().type()).isEqualTo(MessageType.STATUS);
        assertThat(result.get().content()).isEqualTo("still running");
    }

    @Test
    void detect_prefixNoSpace_stripsPrefix() {
        var result = SpeechActDetection.detect("[DONE]task finished");
        assertThat(result).isPresent();
        assertThat(result.get().content()).isEqualTo("task finished");
    }

    @Test
    void detect_prefixWithColon_stripsColonAndWhitespace() {
        var result = SpeechActDetection.detect("[STATUS]: progress update");
        assertThat(result).isPresent();
        assertThat(result.get().type()).isEqualTo(MessageType.STATUS);
        assertThat(result.get().content()).isEqualTo("progress update");
    }

    @Test
    void detect_prefixEmptyContent_returnsEmptyString() {
        var result = SpeechActDetection.detect("[DONE]");
        assertThat(result).isPresent();
        assertThat(result.get().type()).isEqualTo(MessageType.DONE);
        assertThat(result.get().content()).isEqualTo("");
    }

    @Test
    void detect_prefixUnknownType_returnsEmpty() {
        var result = SpeechActDetection.detect("[ESCALATE] help");
        assertThat(result).isEmpty();
    }

    @Test
    void detect_prefixLeadingWhitespace_trimsAndDetects() {
        var result = SpeechActDetection.detect("  [STATUS] working");
        assertThat(result).isPresent();
        assertThat(result.get().type()).isEqualTo(MessageType.STATUS);
        assertThat(result.get().content()).isEqualTo("working");
    }

    // ── Tier 3: No signal ─────────────────────────────────────────────────────

    @Test
    void detect_noSignal_returnsEmpty() {
        assertThat(SpeechActDetection.detect("Task is complete.")).isEmpty();
    }

    @Test
    void detect_nullInput_returnsEmpty() {
        assertThat(SpeechActDetection.detect(null)).isEmpty();
    }

    @Test
    void detect_emptyInput_returnsEmpty() {
        assertThat(SpeechActDetection.detect("")).isEmpty();
    }

    @Test
    void detect_blankInput_returnsEmpty() {
        assertThat(SpeechActDetection.detect("   ")).isEmpty();
    }
}
