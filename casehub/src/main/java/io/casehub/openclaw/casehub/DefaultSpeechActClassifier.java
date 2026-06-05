package io.casehub.openclaw.casehub;

import jakarta.enterprise.context.ApplicationScoped;
import io.casehub.qhorus.api.message.MessageType;

@ApplicationScoped
public class DefaultSpeechActClassifier implements SpeechActClassifier {

    @Override
    public SpeechActResult classify(SpeechActContext ctx) {
        return new SpeechActResult(MessageType.DONE, ctx.output() != null ? ctx.output() : "", DetectionTier.FALLBACK);
    }
}
