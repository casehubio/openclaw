package io.casehub.openclaw.app.mcp;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.jboss.logging.Logger;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.message.DispatchResult;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.message.Commitment;
import io.casehub.qhorus.runtime.message.CommitmentService;
import io.casehub.qhorus.runtime.message.Message;
import io.casehub.qhorus.runtime.message.MessageService;
import io.casehub.qhorus.runtime.store.CommitmentStore;
import io.quarkiverse.mcp.server.TextContent;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolResponse;

/**
 * MCP tools for the CaseHub commitment lifecycle.
 *
 * <p>Higher-level than Qhorus's {@code send_message} — the agent only needs its own
 * agentId and an optional channelId; this class resolves correlationId, inReplyTo, and
 * obligor automatically.
 *
 * <p>Commitment identity: for channel-backed commits, commitmentId == correlationId of
 * the open COMMAND commitment on that channel. For self-commits, a new UUID is generated.
 *
 * <p>Channel resolution: channelId is read from the {@link Commitment} entity on every
 * call to {@link #resolveChannelId(String)}, making the implementation crash-safe — no
 * in-memory map is needed. Terminal commitments (FULFILLED, DECLINED, DELEGATED, etc.)
 * are filtered out so that done()/reject() after escalate()/delegate() return
 * COMMITMENT_ALREADY_CLOSED rather than silently re-dispatching.
 */
@ApplicationScoped
public class CommitmentTools {

    private static final Logger log = Logger.getLogger(CommitmentTools.class);

    private final MessageService messageService;
    private final CommitmentService commitmentService;
    private final CommitmentStore commitmentStore;

    @Inject
    public CommitmentTools(MessageService messageService,
                           CommitmentService commitmentService,
                           CommitmentStore commitmentStore) {
        this.messageService = messageService;
        this.commitmentService = commitmentService;
        this.commitmentStore = commitmentStore;
    }

    // ---- casehub_commit ----

    @Tool(description = "Register a CaseHub commitment and arm a Watchdog. "
            + "When channelId is provided, acknowledges an open COMMAND commitment on that channel "
            + "by dispatching STATUS. Without channelId, creates a self-tracked commitment. "
            + "Returns commitmentId (pass to casehub_done when the task is complete).")
    public ToolResponse commit(
            @ToolArg(description = "Your OpenClaw agentId") String agentId,
            @ToolArg(description = "Task description") String task,
            @ToolArg(description = "Deadline in ISO-8601 format (e.g. 2026-06-01T17:00:00Z); "
                    + "used only for self-commits", required = false) String deadline,
            @ToolArg(description = "Qhorus channel UUID where the original COMMAND was received; "
                    + "omit for self-commits", required = false) String channelId) {

        if (channelId != null) {
            return channelBacked_commit(agentId, task, channelId);
        }
        return selfCommit(agentId, task, deadline);
    }

    private ToolResponse channelBacked_commit(String agentId, String task, String channelIdStr) {
        UUID channelId;
        try {
            channelId = UUID.fromString(channelIdStr);
        } catch (IllegalArgumentException e) {
            return ToolResponse.error("INVALID_CHANNEL_ID: " + channelIdStr);
        }

        List<Commitment> open = commitmentStore.findOpenByObligor(agentId, channelId);
        if (open.isEmpty()) {
            return ToolResponse.error("No open COMMAND commitment found for agent '"
                    + agentId + "' on channel " + channelId);
        }

        Commitment commitment = open.get(0);
        String correlationId = commitment.correlationId;
        Instant deadline = commitment.expiresAt;

        messageService.dispatch(MessageDispatch.builder()
                .channelId(channelId)
                .sender(agentId)
                .type(MessageType.STATUS)
                .content("Acknowledging: " + task)
                .correlationId(correlationId)
                .actorType(ActorType.AGENT)
                .build());

        return ToolResponse.success("""
                {"commitmentId": "%s", "watchdogDeadline": "%s"}
                """.formatted(correlationId, deadline != null ? deadline.toString() : "none").strip());
    }

    private ToolResponse selfCommit(String agentId, String task, String deadlineStr) {
        Instant deadline = deadlineStr != null ? Instant.parse(deadlineStr) : null;
        String correlationId = UUID.randomUUID().toString();

        commitmentService.open(
                UUID.randomUUID(),
                correlationId,
                null,
                MessageType.COMMAND,
                agentId,
                agentId,
                deadline);

        // Self-commits have no channel to dispatch to

        return ToolResponse.success("""
                {"commitmentId": "%s", "watchdogDeadline": "%s"}
                """.formatted(correlationId, deadline != null ? deadline.toString() : "none").strip());
    }

    // ---- casehub_done ----

    @Tool(description = "Close a CaseHub commitment. Dispatches DONE to the originating Qhorus "
            + "channel (if channel-backed) or calls CommitmentService.fulfill() directly "
            + "(self-commit). Disarms the Watchdog. Always call this when a task is complete.")
    public ToolResponse done(
            @ToolArg(description = "Your OpenClaw agentId") String agentId,
            @ToolArg(description = "commitmentId returned by casehub_commit") String commitmentId,
            @ToolArg(description = "Optional outcome description", required = false) String outcome) {

        return resolveChannelId(commitmentId)
                .map(channelId -> channelBacked_done(agentId, commitmentId, channelId, outcome))
                .orElseGet(() -> selfCommit_done(commitmentId));
    }

    private ToolResponse channelBacked_done(String agentId, String correlationId,
                                             UUID channelId, String outcome) {
        long commandMessageId = findCommandMessageId(correlationId);
        if (commandMessageId < 0) {
            return ToolResponse.error("COMMAND_NOT_FOUND: no COMMAND message found for correlationId '"
                    + correlationId + "' — cannot dispatch DONE (inReplyTo is required)");
        }

        DispatchResult result = messageService.dispatch(MessageDispatch.builder()
                .channelId(channelId)
                .sender(agentId)
                .type(MessageType.DONE)
                .content(outcome != null ? outcome : "Done")
                .correlationId(correlationId)
                .inReplyTo(commandMessageId)
                .actorType(ActorType.AGENT)
                .build());

        return ToolResponse.success("""
                {"closed": true, "ledgerSeq": %d}
                """.formatted(result.messageId()).strip());
    }

    private ToolResponse selfCommit_done(String correlationId) {
        Optional<Commitment> existing = commitmentStore.findByCorrelationId(correlationId);
        if (existing.isEmpty()) {
            return ToolResponse.error("COMMITMENT_NOT_FOUND: " + correlationId);
        }
        if (existing.get().state.isTerminal()) {
            return ToolResponse.error("COMMITMENT_ALREADY_CLOSED: " + correlationId
                    + " is in state " + existing.get().state);
        }
        commitmentService.fulfill(correlationId);
        return ToolResponse.success("{\"closed\": true}");
    }

    // ---- casehub_reject ----

    @Tool(description = "Decline a CaseHub commitment — DECLINE speech act. Use when you cannot "
            + "complete the task. Reason is required and recorded in the ledger.")
    public ToolResponse reject(
            @ToolArg(description = "Your OpenClaw agentId") String agentId,
            @ToolArg(description = "commitmentId returned by casehub_commit") String commitmentId,
            @ToolArg(description = "Reason for declining") String reason) {

        return resolveChannelId(commitmentId)
                .map(channelId -> channelBacked_reject(agentId, commitmentId, channelId, reason))
                .orElseGet(() -> selfCommit_reject(commitmentId, reason));
    }

    private ToolResponse channelBacked_reject(String agentId, String correlationId,
                                               UUID channelId, String reason) {
        long commandMessageId = findCommandMessageId(correlationId);
        if (commandMessageId < 0) {
            return ToolResponse.error("COMMAND_NOT_FOUND: no COMMAND message found for correlationId '"
                    + correlationId + "' — cannot dispatch DECLINE (inReplyTo is required)");
        }

        messageService.dispatch(MessageDispatch.builder()
                .channelId(channelId)
                .sender(agentId)
                .type(MessageType.DECLINE)
                .content(reason)
                .correlationId(correlationId)
                .inReplyTo(commandMessageId)
                .actorType(ActorType.AGENT)
                .build());

        return ToolResponse.success("{\"declined\": true}");
    }

    private ToolResponse selfCommit_reject(String correlationId, String reason) {
        Optional<Commitment> existing = commitmentStore.findByCorrelationId(correlationId);
        if (existing.isEmpty()) {
            return ToolResponse.error("COMMITMENT_NOT_FOUND: " + correlationId);
        }
        if (existing.get().state.isTerminal()) {
            return ToolResponse.error("COMMITMENT_ALREADY_CLOSED: " + correlationId
                    + " is in state " + existing.get().state);
        }
        commitmentService.decline(correlationId);
        return ToolResponse.success("{\"declined\": true}");
    }

    // ---- casehub_checkpoint ----

    @Tool(description = "Report progress on a commitment. Dispatches STATUS to the originating "
            + "channel and resets the Watchdog TTL. Use for long-running tasks to prevent "
            + "false escalation.")
    public ToolResponse checkpoint(
            @ToolArg(description = "Your OpenClaw agentId") String agentId,
            @ToolArg(description = "commitmentId returned by casehub_commit") String commitmentId,
            @ToolArg(description = "Progress note") String note) {

        Optional<UUID> channelOpt = resolveChannelId(commitmentId);
        if (channelOpt.isEmpty()) {
            return ToolResponse.error("COMMITMENT_NOT_FOUND: " + commitmentId);
        }
        UUID channelId = channelOpt.get();

        messageService.dispatch(MessageDispatch.builder()
                .channelId(channelId)
                .sender(agentId)
                .type(MessageType.STATUS)
                .content(note)
                .correlationId(commitmentId)
                .actorType(ActorType.AGENT)
                .build());

        return ToolResponse.success("{\"watchdogReset\": true}");
    }

    // ---- casehub_escalate ----

    @Tool(description = "Escalate a commitment to a human or named agent. Dispatches HANDOFF "
            + "to the originating channel. The Watchdog continues running — the escalation "
            + "target is responsible for resolving the commitment.")
    public ToolResponse escalate(
            @ToolArg(description = "Your OpenClaw agentId") String agentId,
            @ToolArg(description = "commitmentId returned by casehub_commit") String commitmentId,
            @ToolArg(description = "Reason for escalation") String reason,
            @ToolArg(description = "Target agent or human identifier", required = false) String toAgent) {

        Optional<UUID> channelOpt = resolveChannelId(commitmentId);
        if (channelOpt.isEmpty()) {
            return ToolResponse.error("COMMITMENT_NOT_FOUND: " + commitmentId);
        }
        UUID channelId = channelOpt.get();

        long commandMessageId = findCommandMessageId(commitmentId);
        if (commandMessageId < 0) {
            return ToolResponse.error("COMMAND_NOT_FOUND: no COMMAND message found for correlationId '"
                    + commitmentId + "' — cannot dispatch HANDOFF (inReplyTo is required)");
        }

        messageService.dispatch(MessageDispatch.builder()
                .channelId(channelId)
                .sender(agentId)
                .type(MessageType.HANDOFF)
                .content(reason)
                .correlationId(commitmentId)
                .inReplyTo(commandMessageId)
                .target(toAgent)
                .actorType(ActorType.AGENT)
                .build());

        return ToolResponse.success("{\"escalated\": true, \"escalationId\": \"%s\"}"
                .formatted(commitmentId));
    }

    // ---- casehub_block ----

    @Transactional
    @Tool(description = "Temporarily block a commitment when an external dependency prevents progress. "
            + "Extends the Watchdog deadline (expiresAt) to prevent premature expiry. "
            + "Call casehub_checkpoint with 'UNBLOCKED: <note>' when the blocker resolves. "
            + "Only the obligor of the commitment may call this tool.")
    public ToolResponse block(
            @ToolArg(description = "Your OpenClaw agentId") String agentId,
            @ToolArg(description = "commitmentId returned by casehub_commit") String commitmentId,
            @ToolArg(description = "What is blocking progress") String reason,
            @ToolArg(description = "Expected resolution time in ISO-8601 format; must be in the future") String blockedUntil) {

        Instant newDeadline;
        try {
            newDeadline = Instant.parse(blockedUntil);
        } catch (Exception e) {
            return ToolResponse.error("INVALID_DEADLINE: " + blockedUntil);
        }

        if (!newDeadline.isAfter(Instant.now())) {
            return ToolResponse.error("DEADLINE_IN_PAST: blockedUntil must be a future timestamp, got: " + blockedUntil);
        }

        Optional<Commitment> cOpt = commitmentStore.findByCorrelationId(commitmentId);
        if (cOpt.isEmpty()) {
            return ToolResponse.error("COMMITMENT_NOT_FOUND: " + commitmentId);
        }

        Commitment commitment = cOpt.get();
        if (commitment.state.isTerminal()) {
            return ToolResponse.error("COMMITMENT_ALREADY_CLOSED: commitment " + commitmentId
                    + " is already in terminal state " + commitment.state);
        }
        if (!agentId.equals(commitment.obligor)) {
            return ToolResponse.error("COMMITMENT_UNAUTHORIZED: agentId '" + agentId
                    + "' is not the obligor for commitment " + commitmentId);
        }

        commitment.expiresAt = newDeadline;
        commitmentStore.save(commitment);

        if (commitment.channelId != null) {
            messageService.dispatch(MessageDispatch.builder()
                    .channelId(commitment.channelId)
                    .sender(agentId)
                    .type(MessageType.STATUS)
                    .content("BLOCKED: " + reason)
                    .correlationId(commitmentId)
                    .actorType(ActorType.AGENT)
                    .build());
        }

        return ToolResponse.success("""
                {"blocked": true, "newWatchdogDeadline": "%s"}
                """.formatted(newDeadline).strip());
    }

    // ---- casehub_delegate ----

    @Tool(description = "Intentionally transfer a commitment to a named agent or person. "
            + "Dispatches HANDOFF to the originating channel. The Watchdog continues — "
            + "the delegatee is now responsible for fulfilling the commitment. "
            + "Use when delegating responsibility, NOT when escalating for authority or capability reasons "
            + "(use casehub_escalate for that).")
    public ToolResponse delegate(
            @ToolArg(description = "Your OpenClaw agentId") String agentId,
            @ToolArg(description = "commitmentId returned by casehub_commit") String commitmentId,
            @ToolArg(description = "Reason for delegation — recorded in the Qhorus ledger") String reason,
            @ToolArg(description = "Target agent ID or human identifier") String toAgent) {

        Optional<UUID> channelOpt = resolveChannelId(commitmentId);
        if (channelOpt.isEmpty()) {
            return ToolResponse.error("COMMITMENT_NOT_FOUND: " + commitmentId);
        }
        UUID channelId = channelOpt.get();

        long commandMessageId = findCommandMessageId(commitmentId);
        if (commandMessageId < 0) {
            return ToolResponse.error("COMMAND_NOT_FOUND: no COMMAND message found for correlationId '"
                    + commitmentId + "' — cannot dispatch HANDOFF (inReplyTo is required)");
        }

        messageService.dispatch(MessageDispatch.builder()
                .channelId(channelId)
                .sender(agentId)
                .type(MessageType.HANDOFF)
                .content(reason)
                .correlationId(commitmentId)
                .inReplyTo(commandMessageId)
                .target(toAgent)
                .actorType(ActorType.AGENT)
                .build());

        return ToolResponse.success("""
                {"delegated": true, "delegatedTo": "%s"}
                """.formatted(toAgent).strip());
    }

    // ---- helpers ----

    /**
     * Resolves the channelId for a commitment, filtering out terminal commitments.
     *
     * <p>Returns {@link Optional#empty()} if:
     * <ul>
     *   <li>no commitment exists for {@code correlationId}, or</li>
     *   <li>the commitment is in a terminal state (FULFILLED, DECLINED, DELEGATED, etc.), or</li>
     *   <li>the commitment has no channelId (self-commit).</li>
     * </ul>
     *
     * <p>Callers that receive empty should fall through to the self-commit path
     * (done/reject) or return COMMITMENT_NOT_FOUND (checkpoint/escalate/delegate).
     */
    private Optional<UUID> resolveChannelId(String correlationId) {
        return commitmentStore.findByCorrelationId(correlationId)
                .filter(c -> !c.state.isTerminal())
                .map(c -> c.channelId)
                .filter(id -> id != null);
    }

    private long findCommandMessageId(String correlationId) {
        return messageService.findAllByCorrelationId(correlationId)
                .stream()
                .filter(m -> m.messageType == MessageType.COMMAND)
                .mapToLong(m -> m.id)
                .findFirst()
                .orElse(-1L);
    }
}
