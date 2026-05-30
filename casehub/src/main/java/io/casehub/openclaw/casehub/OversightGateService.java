package io.casehub.openclaw.casehub;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import io.casehub.openclaw.client.OpenClawClientConfig;
import io.casehub.openclaw.client.OpenClawHookClient;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.message.Commitment;
import io.casehub.qhorus.runtime.message.Message;
import io.casehub.qhorus.runtime.message.MessageService;
import io.casehub.qhorus.runtime.store.CommitmentStore;

/**
 * Owns the oversight gate lifecycle: classifies agent output, dispatches to the
 * work channel (AUTONOMOUS) or opens a human oversight gate (GATE_REQUIRED).
 *
 * <p>Phase 1: {@link DefaultActionRiskClassifier} always returns AUTONOMOUS, so
 * the gate path is never triggered in production. The gate is fully implemented
 * and unit-tested.
 *
 * <p>Message type semantics:
 * <ul>
 *   <li>Autonomous path dispatches STATUS — DONE requires inReplyTo and correlationId
 *       per the MessageDispatch builder contract; there is no prior COMMAND to reply to
 *       on the autonomous path.</li>
 *   <li>Gate path dispatches COMMAND to oversight channel — creates a Commitment
 *       via Qhorus that fulfill() later resolves.</li>
 *   <li>Fulfill dispatches RESPONSE or DECLINE to oversight (resolving the
 *       Commitment via inReplyTo + correlationId) and STATUS to the work channel
 *       (notification only — no COMMAND to resolve on the work channel).</li>
 * </ul>
 */
@ApplicationScoped
public class OversightGateService {

    private static final Logger log = Logger.getLogger(OversightGateService.class);
    static final String GATE_SENDER = "openclaw-gate";

    private final ChannelService channelService;
    private final MessageService messageService;
    private final CommitmentStore commitmentStore;
    private final OpenClawHookClient hookClient;
    private final OpenClawClientConfig clientConfig;
    private final OpenClawCasehubConfig casehubConfig;
    private final SpeechActClassifier speechActClassifier;
    private final ActionRiskClassifier actionRiskClassifier;

    @Inject
    public OversightGateService(ChannelService channelService,
                                 MessageService messageService,
                                 CommitmentStore commitmentStore,
                                 OpenClawHookClient hookClient,
                                 OpenClawClientConfig clientConfig,
                                 OpenClawCasehubConfig casehubConfig,
                                 SpeechActClassifier speechActClassifier,
                                 ActionRiskClassifier actionRiskClassifier) {
        this.channelService = channelService;
        this.messageService = messageService;
        this.commitmentStore = commitmentStore;
        this.hookClient = hookClient;
        this.clientConfig = clientConfig;
        this.casehubConfig = casehubConfig;
        this.speechActClassifier = speechActClassifier;
        this.actionRiskClassifier = actionRiskClassifier;
    }

    /**
     * Classifies the agent output and either dispatches to the work channel (AUTONOMOUS)
     * or opens a human oversight gate (GATE_REQUIRED).
     *
     * <p>Fail-open: any exception is caught and logged. Never propagates to callers.
     */
    public void evaluate(UUID workChannelId, String agentId, String output) {
        try {
            Channel workChannel = channelService.findById(workChannelId).orElse(null);
            if (workChannel == null) {
                log.warnf("evaluate() called for unknown workChannelId=%s — failing open", workChannelId);
                return;
            }

            UUID caseId = CaseChannelNames.extractCaseId(workChannel.name);
            if (caseId == null) {
                log.warnf("Could not extract caseId from channel name '%s' — failing open", workChannel.name);
                return;
            }

            MessageType messageType = speechActClassifier.classify(
                    new SpeechActContext(agentId, output, null));

            RiskDecision decision = actionRiskClassifier.classify(
                    new PlannedAction(agentId, caseId, output, null, Map.of()));

            if (decision instanceof RiskDecision.Autonomous) {
                // DONE/DECLINE/FAILURE/RESPONSE require inReplyTo — not available on autonomous path.
                // Fall back to STATUS; tracked in openclaw#16.
                MessageType dispatchType = requiresReplyFields(messageType) ? MessageType.STATUS : messageType;
                messageService.dispatch(MessageDispatch.builder()
                        .channelId(workChannelId)
                        .sender(agentId)
                        .type(dispatchType)
                        .content(output != null ? output : "")
                        .actorType(ActorType.AGENT)
                        .build());
                return;
            }

            RiskDecision.GateRequired gate = (RiskDecision.GateRequired) decision;
            openGate(caseId, workChannelId, agentId, output, gate);

        } catch (Exception e) {
            log.errorf("OversightGateService.evaluate() failed for channel=%s agent=%s: %s",
                    workChannelId, agentId, e.getMessage());
        }
    }

    private void openGate(UUID caseId, UUID workChannelId, String agentId,
                          String output, RiskDecision.GateRequired gate) {
        Channel oversightChannel = channelService.findByName(
                CaseChannelNames.oversightChannelName(caseId)).orElse(null);
        if (oversightChannel == null) {
            log.errorf("Oversight channel not found for caseId=%s — cannot open gate; failing open. " +
                    "Oversight channel should be created by OpenClawCaseChannelProvider.openChannel().", caseId);
            return;
        }

        UUID gateId = UUID.randomUUID();
        String oversightPrompt = buildOversightPrompt(agentId, output, gate);

        // COMMAND content = original agent output (machine-retrievable); prompt goes to
        // OpenClaw invoke() as the message parameter separately.
        // inReplyTo for RESPONSE/DECLINE is resolved durably in fulfill() via findAllByCorrelationId.
        messageService.dispatch(MessageDispatch.builder()
                .channelId(oversightChannel.id)
                .sender(agentId)
                .type(MessageType.COMMAND)
                .content(output != null ? output : "")
                .correlationId(gateId.toString())
                .actorType(ActorType.AGENT)
                .build());

        String oversightDeliveryUrl =
                clientConfig.delivery().baseUrl() + "/openclaw/delivery/oversight/" + gateId;
        String oversightAgentId = casehubConfig.oversight().agentId()
                .filter(s -> !s.isBlank())
                .orElse(agentId);

        hookClient.invoke(oversightAgentId, oversightPrompt,
                clientConfig.agent().defaultModel(),
                clientConfig.agent().defaultTimeoutSeconds(),
                oversightDeliveryUrl);

        log.infof("Oversight gate opened: gateId=%s caseId=%s agent=%s reason=%s",
                gateId, caseId, agentId, gate.reason());
    }

    private String buildOversightPrompt(String agentId, String output, RiskDecision.GateRequired gate) {
        StringBuilder sb = new StringBuilder();
        sb.append("OpenClaw agent \"").append(agentId).append("\" proposes the following action:\n\n");
        sb.append(output != null ? output : "(no output)").append("\n\n");
        sb.append("Reason for oversight: ").append(gate.reason()).append("\n");
        if (!gate.reversible()) {
            sb.append("⚠️ This action cannot be undone once approved.\n");
        }
        sb.append("\nReply with \"approved\" to proceed or \"rejected\" to decline.");
        return sb.toString();
    }

    /**
     * Processes the oversight agent's response to a gate. Dispatches RESPONSE or
     * DECLINE to the oversight channel (resolving the Commitment via inReplyTo +
     * correlationId) and STATUS to the work channel (notification — no COMMAND
     * to resolve on the work channel side).
     *
     * <p>Fail-open on all error conditions: unknown gateId, missing channels, etc.
     */
    public void fulfill(UUID gateId, String rawOutput) {
        try {
            // Look up the original COMMAND message (dispatched in openGate) to get its Long ID for inReplyTo
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

            if (approved) {
                messageService.dispatch(MessageDispatch.builder()
                        .channelId(oversightChannel.id)
                        .sender(GATE_SENDER)
                        .type(MessageType.RESPONSE)
                        .content(rawOutput != null ? rawOutput : "approved")
                        .correlationId(gateId.toString())
                        .inReplyTo(commandMessageId)
                        .actorType(ActorType.AGENT)
                        .build());
                messageService.dispatch(MessageDispatch.builder()
                        .channelId(workChannel.id)
                        .sender(GATE_SENDER)
                        .type(MessageType.STATUS)
                        .content("Gate approved")
                        .actorType(ActorType.AGENT)
                        .build());
                log.infof("Gate approved: gateId=%s caseId=%s", gateId, caseId);
            } else {
                messageService.dispatch(MessageDispatch.builder()
                        .channelId(oversightChannel.id)
                        .sender(GATE_SENDER)
                        .type(MessageType.DECLINE)
                        .content(rawOutput != null ? rawOutput : "rejected")
                        .correlationId(gateId.toString())
                        .inReplyTo(commandMessageId)
                        .actorType(ActorType.AGENT)
                        .build());
                messageService.dispatch(MessageDispatch.builder()
                        .channelId(workChannel.id)
                        .sender(GATE_SENDER)
                        .type(MessageType.STATUS)
                        .content("Human rejected the proposed action via oversight gate")
                        .actorType(ActorType.AGENT)
                        .build());
                log.infof("Gate rejected: gateId=%s caseId=%s", gateId, caseId);
            }
        } catch (Exception e) {
            log.errorf("OversightGateService.fulfill() failed for gateId=%s: %s", gateId, e.getMessage());
        }
    }

    private static boolean requiresReplyFields(MessageType type) {
        return switch (type) {
            case DONE, DECLINE, FAILURE, RESPONSE -> true;
            default -> false;
        };
    }

    private boolean parseApproval(UUID gateId, String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) {
            log.warnf("fulfill() received null/blank output for gateId=%s — treating as rejected", gateId);
            return false;
        }
        // First token stripped of trailing punctuation, lowered. "approved, please go ahead" → "approved".
        String firstToken = rawOutput.trim().toLowerCase().split("\\s+")[0].replaceAll("[^a-z]+$", "");
        return firstToken.equals("approved");
    }
}
