package io.casehub.openclaw.casehub;

import org.junit.jupiter.api.Test;
import io.casehub.qhorus.api.message.MessageType;
import static org.assertj.core.api.Assertions.assertThat;

class DefaultSpeechActClassifierTest {

    DefaultSpeechActClassifier classifier = new DefaultSpeechActClassifier();

    @Test
    void classify_normalOutput_returnsDone() {
        SpeechActResult result = classifier.classify(new SpeechActContext("agent", "Analysis complete."));
        assertThat(result.type()).isEqualTo(MessageType.DONE);
    }

    @Test
    void classify_emptyOutput_returnsDone() {
        SpeechActResult result = classifier.classify(new SpeechActContext("agent", ""));
        assertThat(result.type()).isEqualTo(MessageType.DONE);
    }

    @Test
    void classify_nullOutput_returnsDone() {
        SpeechActResult result = classifier.classify(new SpeechActContext("agent", null));
        assertThat(result.type()).isEqualTo(MessageType.DONE);
    }
}
