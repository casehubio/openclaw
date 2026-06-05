package io.casehub.openclaw.casehub;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import io.casehub.qhorus.api.message.MessageType;

@ApplicationScoped
public class DefaultSpeechActClassifier implements SpeechActClassifier {

    private static final Logger log = Logger.getLogger(DefaultSpeechActClassifier.class);

    @Override
    public SpeechActResult classify(SpeechActContext ctx) {
        return SpeechActDetection.detect(ctx.output())
                .orElseGet(() -> {
                    log.infof("SpeechActDetection: no explicit signal from agentId=%s"
                            + " — STATUS fallback applied", ctx.agentId());
                    String content = ctx.output() != null ? ctx.output() : "";
                    return new SpeechActResult(MessageType.STATUS, content, DetectionTier.FALLBACK);
                });
    }
}
