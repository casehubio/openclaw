package io.casehub.openclaw.casehub;

import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.message.MessageService;

/**
 * Owns the atomic dispatch boundary for oversight gate decisions.
 *
 * <p>Both dispatches in an approve/reject decision commit in the same transaction,
 * so a crash between them cannot leave the case step hanging with a resolved
 * Commitment but no closure on the work channel (openclaw#15).
 *
 * <p>When {@code gateContext} is present (Phase 2 gate path), the work-channel
 * dispatch closes the agent's original commitment: DONE on approval, DECLINE on
 * rejection. When absent (restart recovery / content parse error), the work channel
 * receives STATUS only (no commitment closure — logged by caller).
 *
 * <p>Accepts primitives only — no JPA entities cross a @Transactional boundary.
 * Package-private: only OversightGateService may call this.
 */
@ApplicationScoped
class OversightGateDispatcher {

    private final MessageService messageService;

    @Inject
    OversightGateDispatcher(MessageService messageService) {
        this.messageService = messageService;
    }

    @Transactional
    void dispatch(boolean approved,
                  UUID oversightChannelId,
                  UUID workChannelId,
                  long commandMessageId,
                  UUID gateId,
                  String rawOutput,
                  Optional<GateContext> gateContext) {
        if (approved) {
            messageService.dispatch(MessageDispatch.builder()
                    .channelId(oversightChannelId)
                    .sender(OversightGateService.GATE_SENDER)
                    .type(MessageType.RESPONSE)
                    .content(rawOutput != null ? rawOutput : "approved")
                    .correlationId(gateId.toString())
                    .inReplyTo(commandMessageId)
                    .actorType(ActorType.AGENT)
                    .build());
            if (gateContext.isPresent()) {
                GateContext ctx = gateContext.get();
                messageService.dispatch(MessageDispatch.builder()
                        .channelId(ctx.workChannelId())
                        .sender(OversightGateService.GATE_SENDER)
                        .type(MessageType.DONE)
                        .content("Action approved by oversight gate")
                        .correlationId(ctx.originalCommitmentId())
                        .inReplyTo(ctx.commandMessageId())
                        .actorType(ActorType.AGENT)
                        .build());
            } else {
                messageService.dispatch(MessageDispatch.builder()
                        .channelId(workChannelId)
                        .sender(OversightGateService.GATE_SENDER)
                        .type(MessageType.STATUS)
                        .content("Gate approved")
                        .actorType(ActorType.AGENT)
                        .build());
            }
        } else {
            messageService.dispatch(MessageDispatch.builder()
                    .channelId(oversightChannelId)
                    .sender(OversightGateService.GATE_SENDER)
                    .type(MessageType.DECLINE)
                    .content(rawOutput != null ? rawOutput : "rejected")
                    .correlationId(gateId.toString())
                    .inReplyTo(commandMessageId)
                    .actorType(ActorType.AGENT)
                    .build());
            if (gateContext.isPresent()) {
                GateContext ctx = gateContext.get();
                messageService.dispatch(MessageDispatch.builder()
                        .channelId(ctx.workChannelId())
                        .sender(OversightGateService.GATE_SENDER)
                        .type(MessageType.DECLINE)
                        .content("Action rejected by oversight gate")
                        .correlationId(ctx.originalCommitmentId())
                        .inReplyTo(ctx.commandMessageId())
                        .actorType(ActorType.AGENT)
                        .build());
            } else {
                messageService.dispatch(MessageDispatch.builder()
                        .channelId(workChannelId)
                        .sender(OversightGateService.GATE_SENDER)
                        .type(MessageType.STATUS)
                        .content("Human rejected the proposed action via oversight gate")
                        .actorType(ActorType.AGENT)
                        .build());
            }
        }
    }
}
