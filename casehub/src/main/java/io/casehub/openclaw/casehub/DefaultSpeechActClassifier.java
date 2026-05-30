package io.casehub.openclaw.casehub;

import jakarta.enterprise.context.ApplicationScoped;
import io.casehub.qhorus.api.message.MessageType;

@ApplicationScoped
public class DefaultSpeechActClassifier implements SpeechActClassifier {

    @Override
    public MessageType classify(SpeechActContext context) {
        return MessageType.DONE;
    }
}
