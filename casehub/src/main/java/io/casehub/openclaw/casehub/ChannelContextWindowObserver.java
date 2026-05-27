package io.casehub.openclaw.casehub;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import io.casehub.openclaw.context.ChannelContextWindowService;
import io.casehub.qhorus.api.gateway.MessageObserver;
import io.casehub.qhorus.api.gateway.MessageReceivedEvent;

/**
 * Feeds the ChannelContextWindow ring buffer from every Qhorus message dispatch.
 *
 * <p>EVENT messages are excluded — {@link io.casehub.qhorus.api.message.MessageType#isAgentVisible()}
 * returns false for EVENT; their content is null per PP-20260508-90428f.
 *
 * <p>Per the MessageObserver SPI contract: never query Qhorus state inside this method
 * (the dispatcher fires before the enclosing transaction commits). Never propagate exceptions.
 */
@ApplicationScoped
public class ChannelContextWindowObserver implements MessageObserver {

    private static final Logger log = Logger.getLogger(ChannelContextWindowObserver.class);

    @Inject
    ChannelContextWindowService service;

    @Override
    public void onMessage(MessageReceivedEvent event) {
        if (!event.messageType().isAgentVisible()) return;
        try {
            service.add(event);
        } catch (Exception e) {
            log.errorf(e, "ChannelContextWindow write failed for channel %s — ignoring",
                    event.channelName());
        }
    }
}
