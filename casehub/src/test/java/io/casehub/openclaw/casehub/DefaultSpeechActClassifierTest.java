package io.casehub.openclaw.casehub;

import org.junit.jupiter.api.Test;
import io.casehub.qhorus.api.message.MessageType;
import static org.assertj.core.api.Assertions.assertThat;

class DefaultSpeechActClassifierTest {

    DefaultSpeechActClassifier classifier = new DefaultSpeechActClassifier();

    @Test
    void classify_normalOutput_returnsDone() {
        assertThat(classifier.classify(new SpeechActContext("agent", "Analysis complete.", "finance")))
                .isEqualTo(MessageType.DONE);
    }

    @Test
    void classify_emptyOutput_returnsDone() {
        assertThat(classifier.classify(new SpeechActContext("agent", "", null)))
                .isEqualTo(MessageType.DONE);
    }

    @Test
    void classify_nullActionType_returnsDone() {
        assertThat(classifier.classify(new SpeechActContext("agent", "result", null)))
                .isEqualTo(MessageType.DONE);
    }
}
