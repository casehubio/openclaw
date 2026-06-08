package io.casehub.openclaw.casehub;

import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.message.Commitment;
import io.casehub.qhorus.runtime.message.MessageService;
import io.casehub.qhorus.runtime.store.CommitmentStore;

/**
 * Owns the oversight gate lifecycle.
 *
 * <p>{@link #evaluate(UUID, String, String)} archives the agent's webhook output as a
 * non-resolving STATUS message on the work channel. Completion signaling is the
 * responsibility of the MCP tools ({@code casehub_done}, {@code casehub_reject}, etc.)
 * which dispatch typed Qhorus messages directly (openclaw#28, tool-call-first architecture).
 *
 * <p>{@link #fulfill(UUID, String)} processes human responses to oversight gates opened
 * by the Phase 2 gate path (openclaw#30). The fulfillment path is retained intact so
 * Phase 2 can re-wire the gate entry point without re-implementing it.
 *
 * <p>Fail-open: both methods catch and log all exceptions. Neither propagates to callers.
 */
@ApplicationScoped
public class OversightGateService {

    private static final Logger log = Logger.getLogger(OversightGateService.class);
    static final String GATE_SENDER = "openclaw-gate";

    private final ChannelService channelService;
    private final MessageService messageService;
    private final CommitmentStore commitmentStore;
    private final OversightGateDispatcher gateDispatcher;

    @Inject
    public OversightGateService(final ChannelService channelService,
                                 final MessageService messageService,
                                 final CommitmentStore commitmentStore,
                                 final OversightGateDispatcher gateDispatcher) {
        this.channelService = channelService;
        this.messageService = messageService;
        this.commitmentStore = commitmentStore;
        this.gateDispatcher = gateDispatcher;
    }

    /**
     * Archives the agent's webhook output as a non-resolving STATUS on the work channel.
     *
     * <p>Completion signaling is owned by MCP tool calls ({@code casehub_done} etc.) which
     * dispatch typed Qhorus messages during the agent turn. The STATUS dispatched here is
     * purely archival — it carries no {@code correlationId}, so Qhorus skips the
     * {@code commitmentService.acknowledge()} transition and records only the message text.
     *
     * <p>Fail-open: any exception is caught and logged. Never propagates.
     */
    public void evaluate(final UUID workChannelId, final String agentId, final String output) {
        try {
            if (output == null || output.isBlank()) return;
            messageService.dispatch(MessageDispatch.builder()
                    .channelId(workChannelId)
                    .sender(agentId)
                    .type(MessageType.STATUS)
                    .content(output)
                    .actorType(ActorType.AGENT)
                    .build());
        } catch (Exception e) {
            log.errorf("evaluate() failed to archive webhook output for channel=%s agent=%s: %s",
                    workChannelId, agentId, e.getMessage());
        }
    }

    /**
     * Processes the oversight agent's response to a gate. Dispatches RESPONSE or
     * DECLINE to the oversight channel (resolving the Commitment via inReplyTo +
     * correlationId) and STATUS to the work channel (notification — no COMMAND
     * to resolve on the work channel side).
     *
     * <p>Fail-open on all error conditions: unknown gateId, missing channels, etc.
     */
    public void fulfill(final UUID gateId, final String rawOutput) {
        try {
            Long commandMessageId = messageService.findAllByCorrelationId(gateId.toString()).stream()
                    .filter(m -> m.messageType == MessageType.COMMAND)
                    .mapToLong(m -> m.id)
                    .findFirst()
                    .orElse(-1L);
            if (commandMessageId == -1L) {
                log.warnf("fulfill() called for gateId=%s — no COMMAND message found via correlationId; " +
                        "possible restart or duplicate delivery; failing open", gateId);
                return;
            }

            boolean approved = parseApproval(gateId, rawOutput);

            Optional<Commitment> commitmentOpt = commitmentStore.findByCorrelationId(gateId.toString());
            if (commitmentOpt.isEmpty()) {
                log.warnf("fulfill() called for unknown gateId=%s — possible duplicate delivery, ignoring", gateId);
                return;
            }

            Commitment commitment = commitmentOpt.get();
            Channel oversightChannel = channelService.findById(commitment.channelId).orElse(null);
            if (oversightChannel == null) {
                log.errorf("Oversight channel %s not found for gateId=%s — failing open",
                        commitment.channelId, gateId);
                return;
            }

            UUID caseId = CaseChannelNames.extractCaseId(oversightChannel.name);
            if (caseId == null) {
                log.errorf("Could not extract caseId from oversight channel '%s' for gateId=%s — failing open",
                        oversightChannel.name, gateId);
                return;
            }

            Channel workChannel = channelService.findByName(CaseChannelNames.workChannelName(caseId)).orElse(null);
            if (workChannel == null) {
                log.errorf("Work channel not found for caseId=%s gateId=%s — failing open", caseId, gateId);
                return;
            }

            gateDispatcher.dispatch(approved, oversightChannel.id, workChannel.id,
                    commandMessageId, gateId, rawOutput);
            log.infof("Gate %s: gateId=%s caseId=%s", approved ? "approved" : "rejected", gateId, caseId);
        } catch (Exception e) {
            log.errorf("OversightGateService.fulfill() failed for gateId=%s: %s", gateId, e.getMessage());
        }
    }

    private boolean parseApproval(final UUID gateId, final String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) {
            log.warnf("fulfill() received null/blank output for gateId=%s — treating as rejected", gateId);
            return false;
        }
        String firstToken = rawOutput.trim().toLowerCase().split("\\s+")[0].replaceAll("[^a-z]+$", "");
        return firstToken.equals("approved");
    }
}
