package io.casehub.openclaw.casehub;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import io.casehub.api.spi.ActionRiskClassifier;
import io.casehub.api.spi.PlannedAction;
import io.casehub.api.spi.RiskClassifier;
import io.casehub.api.spi.RiskDecision;
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
 * Owns the oversight gate lifecycle.
 *
 * <p>{@link #evaluate(UUID, String, String)} archives the agent's webhook output as a
 * non-resolving STATUS message on the work channel.
 *
 * <p>{@link #openGate(String, String, String)} classifies the proposed action via
 * {@code @RiskClassifier} CDI beans. If {@code GateRequired}, dispatches a COMMAND to
 * the oversight channel and returns {@link GateDecision.GatePending}. If {@code Autonomous}
 * (or no classifiers registered), returns {@link GateDecision.Autonomous} so the caller
 * can proceed with normal DONE dispatch. Fail-open on infrastructure errors.
 *
 * <p>{@link #fulfill(UUID, String)} processes human responses to oversight gates. Parses
 * the original commitment context from the gate COMMAND message content (persisted in Qhorus)
 * and dispatches DONE or DECLINE to close the agent's work commitment.
 *
 * <p>All three methods catch and log all exceptions — none propagate to callers.
 */
@ApplicationScoped
public class OversightGateService {

    private static final Logger log = Logger.getLogger(OversightGateService.class);
    static final String GATE_SENDER = "openclaw-gate";

    private final ChannelService channelService;
    private final MessageService messageService;
    private final CommitmentStore commitmentStore;
    private final OversightGateDispatcher gateDispatcher;
    private final Instance<ActionRiskClassifier> classifiers;

    @Inject
    public OversightGateService(final ChannelService channelService,
                                 final MessageService messageService,
                                 final CommitmentStore commitmentStore,
                                 final OversightGateDispatcher gateDispatcher,
                                 @RiskClassifier final Instance<ActionRiskClassifier> classifiers) {
        this.channelService = channelService;
        this.messageService = messageService;
        this.commitmentStore = commitmentStore;
        this.gateDispatcher = gateDispatcher;
        this.classifiers = classifiers;
    }

    /**
     * Archives the agent's webhook output as a non-resolving STATUS on the work channel.
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
     * Classifies the proposed action and either opens an oversight gate or returns Autonomous.
     *
     * <p>When {@code GateRequired}: dispatches a COMMAND to the case oversight channel with gate
     * context serialized in the content (persisted by Qhorus for crash-safe fulfillment).
     *
     * <p>Fail-open: infrastructure failures (channel not found, dispatch error) → Autonomous.
     * Classifier exception → GateRequired fail-safe (not Autonomous — failure ≠ safe).
     */
    public GateDecision openGate(final String agentId, final String commitmentId,
                                  final String outcome) {
        try {
            Optional<Commitment> cOpt = commitmentStore.findByCorrelationId(commitmentId);
            if (cOpt.isEmpty() || cOpt.get().channelId == null) {
                log.warnf("openGate: no channel-backed commitment for correlationId=%s — failing open",
                        commitmentId);
                return new GateDecision.Autonomous();
            }
            UUID workChannelId = cOpt.get().channelId;

            Channel workChannel = channelService.findById(workChannelId).orElse(null);
            if (workChannel == null) {
                log.warnf("openGate: work channel %s not found — failing open", workChannelId);
                return new GateDecision.Autonomous();
            }

            UUID caseId = CaseChannelNames.extractCaseId(workChannel.name);
            if (caseId == null) {
                log.warnf("openGate: cannot extract caseId from channel name '%s' — failing open",
                        workChannel.name);
                return new GateDecision.Autonomous();
            }

            PlannedAction action = new PlannedAction(agentId, caseId, outcome, "COMPLETION", Map.of());
            RiskDecision decision = classifyMostRestrictive(action);

            if (decision instanceof RiskDecision.Autonomous) {
                return new GateDecision.Autonomous();
            }

            RiskDecision.GateRequired gate = (RiskDecision.GateRequired) decision;

            Channel oversightChannel = channelService
                    .findByName(CaseChannelNames.oversightChannelName(caseId)).orElse(null);
            if (oversightChannel == null) {
                log.warnf("openGate: oversight channel not found for caseId=%s — failing open " +
                        "(oversight not configured)", caseId);
                return new GateDecision.Autonomous();
            }

            long commandMessageId = messageService.findAllByCorrelationId(commitmentId).stream()
                    .filter(m -> m.messageType == MessageType.COMMAND)
                    .mapToLong(m -> m.id)
                    .findFirst()
                    .orElse(-1L);
            if (commandMessageId < 0) {
                log.warnf("openGate: no COMMAND message found for commitmentId=%s — failing open", commitmentId);
                return new GateDecision.Autonomous();
            }

            UUID gateId = UUID.randomUUID();
            GateContext ctx = new GateContext(commitmentId, workChannelId, commandMessageId);

            messageService.dispatch(MessageDispatch.builder()
                    .channelId(oversightChannel.id)
                    .sender(GATE_SENDER)
                    .type(MessageType.COMMAND)
                    .content(serializeGateContent(ctx, gate.reason()))
                    .correlationId(gateId.toString())
                    .actorType(ActorType.AGENT)
                    .build());

            log.infof("Gate opened: gateId=%s agentId=%s commitmentId=%s caseId=%s reason=%s",
                    gateId, agentId, commitmentId, caseId, gate.reason());

            return new GateDecision.GatePending(gateId, gate.reason());
        } catch (Exception e) {
            log.errorf("openGate() failed for agentId=%s commitmentId=%s: %s — failing open",
                    agentId, commitmentId, e.getMessage());
            return new GateDecision.Autonomous();
        }
    }

    /**
     * Processes the oversight agent's response to a gate.
     */
    public void fulfill(final UUID gateId, final String rawOutput) {
        try {
            Optional<Message> gateCmdOpt = messageService.findAllByCorrelationId(gateId.toString())
                    .stream()
                    .filter(m -> m.messageType == MessageType.COMMAND)
                    .findFirst();
            if (gateCmdOpt.isEmpty()) {
                log.warnf("fulfill() called for gateId=%s — no COMMAND message found via correlationId; " +
                        "possible restart or duplicate delivery; failing open", gateId);
                return;
            }
            long commandMessageId = gateCmdOpt.get().id;

            Optional<GateContext> gateContext = parseGateContent(gateCmdOpt.get().content);
            if (gateContext.isEmpty()) {
                log.warnf("fulfill() gateId=%s — gate context missing or unparseable; " +
                        "work commitment will not be closed (falling back to STATUS)", gateId);
            }

            boolean approved = parseApproval(gateId, rawOutput);

            Optional<Commitment> commitmentOpt = commitmentStore.findByCorrelationId(gateId.toString());
            if (commitmentOpt.isEmpty()) {
                log.warnf("fulfill() called for unknown gateId=%s — possible duplicate delivery, ignoring",
                        gateId);
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

            Channel workChannel = channelService.findByName(CaseChannelNames.workChannelName(caseId))
                    .orElse(null);
            if (workChannel == null) {
                log.errorf("Work channel not found for caseId=%s gateId=%s — failing open", caseId, gateId);
                return;
            }

            gateDispatcher.dispatch(approved, oversightChannel.id, workChannel.id,
                    commandMessageId, gateId, rawOutput, gateContext);
            log.infof("Gate %s: gateId=%s caseId=%s", approved ? "approved" : "rejected", gateId, caseId);
        } catch (Exception e) {
            log.errorf("OversightGateService.fulfill() failed for gateId=%s: %s", gateId, e.getMessage());
        }
    }

    private RiskDecision classifyMostRestrictive(PlannedAction action) {
        if (classifiers.isUnsatisfied()) return new RiskDecision.Autonomous();
        RiskDecision result = new RiskDecision.Autonomous();
        for (ActionRiskClassifier classifier : classifiers) {
            try {
                result = mostRestrictive(result, classifier.classify(action));
            } catch (Exception e) {
                log.warnf("ActionRiskClassifier %s threw for action '%s': %s — applying fail-safe GateRequired",
                        classifier.getClass().getSimpleName(), action.description(), e.getMessage());
                return new RiskDecision.GateRequired(
                        "Classifier error — manual review required before proceeding",
                        true, null, null, null);
            }
        }
        return result;
    }

    private RiskDecision mostRestrictive(RiskDecision a, RiskDecision b) {
        if (!(b instanceof RiskDecision.GateRequired)) return a;
        if (!(a instanceof RiskDecision.GateRequired gA)) return b;
        return narrower(gA, (RiskDecision.GateRequired) b);
    }

    private RiskDecision.GateRequired narrower(RiskDecision.GateRequired a, RiskDecision.GateRequired b) {
        int sizeA = a.candidateGroups() == null ? Integer.MAX_VALUE : a.candidateGroups().size();
        int sizeB = b.candidateGroups() == null ? Integer.MAX_VALUE : b.candidateGroups().size();
        if (sizeA != sizeB) return sizeA < sizeB ? a : b;
        if (a.expiresIn() != null && b.expiresIn() != null) {
            return a.expiresIn().compareTo(b.expiresIn()) <= 0 ? a : b;
        }
        return a.expiresIn() != null ? a : b;
    }

    private String serializeGateContent(GateContext ctx, String reason) {
        Properties props = new Properties();
        props.setProperty("originalCommitmentId", ctx.originalCommitmentId());
        props.setProperty("workChannelId", ctx.workChannelId().toString());
        props.setProperty("commandMessageId", String.valueOf(ctx.commandMessageId()));
        props.setProperty("reason", reason != null ? reason : "");
        StringWriter sw = new StringWriter();
        try {
            props.store(sw, null);
        } catch (IOException e) {
            throw new IllegalStateException("StringWriter never throws IOException", e);
        }
        return sw.toString();
    }

    private Optional<GateContext> parseGateContent(String content) {
        if (content == null || content.isBlank()) return Optional.empty();
        try {
            Properties props = new Properties();
            props.load(new StringReader(content));
            String oci = props.getProperty("originalCommitmentId");
            String wci = props.getProperty("workChannelId");
            String cmi = props.getProperty("commandMessageId");
            if (oci == null || wci == null || cmi == null) return Optional.empty();
            return Optional.of(new GateContext(oci, UUID.fromString(wci), Long.parseLong(cmi)));
        } catch (Exception e) {
            log.warnf("parseGateContent: failed to parse gate content: %s", e.getMessage());
            return Optional.empty();
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
