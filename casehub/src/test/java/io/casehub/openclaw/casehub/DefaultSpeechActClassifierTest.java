package io.casehub.openclaw.casehub;

import org.junit.jupiter.api.Test;
import io.casehub.qhorus.api.message.MessageType;
import static org.assertj.core.api.Assertions.assertThat;

class DefaultSpeechActClassifierTest {

    DefaultSpeechActClassifier classifier = new DefaultSpeechActClassifier();

    @Test
    void classify_jsonDone_returnsDoneWithStrippedContent() {
        var result = classifier.classify(
            new SpeechActContext("agent", "{\"type\":\"DONE\",\"content\":\"Task complete.\"}"));
        assertThat(result.type()).isEqualTo(MessageType.DONE);
        assertThat(result.content()).isEqualTo("Task complete.");
        assertThat(result.tier()).isEqualTo(DetectionTier.JSON);
    }

    @Test
    void classify_prefixDecline_returnsDeclineWithStrippedContent() {
        var result = classifier.classify(
            new SpeechActContext("agent", "[DECLINE] Cannot access external API."));
        assertThat(result.type()).isEqualTo(MessageType.DECLINE);
        assertThat(result.content()).isEqualTo("Cannot access external API.");
        assertThat(result.tier()).isEqualTo(DetectionTier.PREFIX);
    }

    @Test
    void classify_noPrefix_returnsStatusFallback() {
        var result = classifier.classify(
            new SpeechActContext("agent", "I have analysed the data."));
        assertThat(result.type()).isEqualTo(MessageType.STATUS);
        assertThat(result.content()).isEqualTo("I have analysed the data.");
        assertThat(result.tier()).isEqualTo(DetectionTier.FALLBACK);
    }

    @Test
    void classify_nullOutput_returnsStatusFallbackWithEmptyContent() {
        var result = classifier.classify(new SpeechActContext("agent", null));
        assertThat(result.type()).isEqualTo(MessageType.STATUS);
        assertThat(result.content()).isEqualTo("");
        assertThat(result.tier()).isEqualTo(DetectionTier.FALLBACK);
    }

    @Test
    void classify_emptyOutput_returnsStatusFallback() {
        var result = classifier.classify(new SpeechActContext("agent", ""));
        assertThat(result.type()).isEqualTo(MessageType.STATUS);
        assertThat(result.content()).isEqualTo("");
        assertThat(result.tier()).isEqualTo(DetectionTier.FALLBACK);
    }

    @Test
    void classify_jsonFailure_returnsFailure() {
        var result = classifier.classify(
            new SpeechActContext("agent", "{\"type\":\"FAILURE\",\"content\":\"DB timeout.\"}"));
        assertThat(result.type()).isEqualTo(MessageType.FAILURE);
        assertThat(result.content()).isEqualTo("DB timeout.");
    }

    @Test
    void classify_prefixStatus_returnsStatus() {
        var result = classifier.classify(
            new SpeechActContext("agent", "[STATUS] Still processing row 3 of 10."));
        assertThat(result.type()).isEqualTo(MessageType.STATUS);
        assertThat(result.content()).isEqualTo("Still processing row 3 of 10.");
    }
}
