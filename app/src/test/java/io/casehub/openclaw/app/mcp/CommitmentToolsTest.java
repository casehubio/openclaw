package io.casehub.openclaw.app.mcp;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.message.CommitmentState;
import io.casehub.qhorus.api.message.DispatchResult;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.message.Commitment;
import io.casehub.qhorus.runtime.message.CommitmentService;
import io.casehub.qhorus.runtime.message.Message;
import io.casehub.qhorus.runtime.message.MessageService;
import io.casehub.qhorus.runtime.store.CommitmentStore;
import io.quarkiverse.mcp.server.ToolResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommitmentToolsTest {

    MessageService messageService;
    CommitmentService commitmentService;
    CommitmentStore commitmentStore;
    CommitmentTools tools;

    @BeforeEach
    void setUp() {
        messageService = mock(MessageService.class);
        commitmentService = mock(CommitmentService.class);
        commitmentStore = mock(CommitmentStore.class);
        tools = new CommitmentTools(messageService, commitmentService, commitmentStore);
    }

    // ---- casehub_commit: channel-backed ----

    @Test
    void commit_withChannelId_dispatchesStatusToAcknowledgeOpenCommand() {
        UUID channelId = UUID.randomUUID();
        String agentId = "finance-agent";
        String correlationId = UUID.randomUUID().toString();
        Instant deadline = Instant.now().plus(1, ChronoUnit.HOURS);

        when(commitmentStore.findOpenByObligor(agentId, channelId))
                .thenReturn(List.of(commitment(correlationId, channelId, agentId, deadline)));
        when(messageService.dispatch(any()))
                .thenReturn(dispatchResult(99L, channelId, agentId, MessageType.STATUS, correlationId));

        ToolResponse response = tools.commit(agentId, "Confirm boiler service", null, channelId.toString());

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());
        MessageDispatch dispatched = captor.getValue();
        assertThat(dispatched.channelId()).isEqualTo(channelId);
        assertThat(dispatched.type()).isEqualTo(MessageType.STATUS);
        assertThat(dispatched.correlationId()).isEqualTo(correlationId);
        assertThat(dispatched.sender()).isEqualTo(agentId);
        assertThat(dispatched.actorType()).isEqualTo(ActorType.AGENT);

        assertThat(response.isError()).isFalse();
        String text = text(response);
        assertThat(text).contains(correlationId);
        assertThat(text).contains("watchdogDeadline");
    }

    @Test
    void commit_withChannelId_noOpenCommitment_returnsError() {
        UUID channelId = UUID.randomUUID();
        when(commitmentStore.findOpenByObligor("home-agent", channelId)).thenReturn(List.of());

        ToolResponse response = tools.commit("home-agent", "check sensors", null, channelId.toString());

        verify(messageService, never()).dispatch(any());
        assertThat(response.isError()).isTrue();
        assertThat(text(response)).contains("No open COMMAND");
    }

    @Test
    void commit_invalidChannelIdFormat_returnsError() {
        ToolResponse response = tools.commit("agent", "task", null, "not-a-uuid");

        assertThat(response.isError()).isTrue();
        assertThat(text(response)).contains("INVALID_CHANNEL_ID");
    }

    // ---- casehub_commit: self-commit (no channelId) ----

    @Test
    void commit_withoutChannelId_opensSelfCommitViaCommitmentService() {
        String agentId = "home-agent";
        String deadline = Instant.now().plus(2, ChronoUnit.HOURS).toString();
        String correlationId = UUID.randomUUID().toString();

        Commitment created = commitment(correlationId, null, agentId,
                Instant.now().plus(2, ChronoUnit.HOURS));
        when(commitmentService.open(any(), any(), any(), eq(MessageType.COMMAND),
                eq(agentId), eq(agentId), any())).thenReturn(created);

        ToolResponse response = tools.commit(agentId, "Run weekly report", deadline, null);

        verify(messageService, never()).dispatch(any());
        verify(commitmentService).open(any(), any(), any(), eq(MessageType.COMMAND),
                eq(agentId), eq(agentId), any());
        assertThat(response.isError()).isFalse();
        assertThat(text(response)).contains("commitmentId");
    }

    // ---- casehub_done ----

    @Test
    void done_channelBacked_dispatchesDoneWithInReplyTo() {
        UUID channelId = UUID.randomUUID();
        String agentId = "finance-agent";
        String correlationId = UUID.randomUUID().toString();
        Instant deadline = Instant.now().plus(1, ChronoUnit.HOURS);

        when(commitmentStore.findByCorrelationId(correlationId))
                .thenReturn(Optional.of(commitment(correlationId, channelId, agentId, deadline)));

        // done() looks up COMMAND message for inReplyTo
        when(messageService.findAllByCorrelationId(correlationId))
                .thenReturn(List.of(
                        message(10L, channelId, MessageType.STATUS, correlationId),
                        message(5L, channelId, MessageType.COMMAND, correlationId)));
        when(messageService.dispatch(any()))
                .thenReturn(dispatchResult(11L, channelId, agentId, MessageType.DONE, correlationId));

        ToolResponse response = tools.done(agentId, correlationId, "Report sent");

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());
        MessageDispatch dispatched = captor.getValue();
        assertThat(dispatched.type()).isEqualTo(MessageType.DONE);
        assertThat(dispatched.channelId()).isEqualTo(channelId);
        assertThat(dispatched.correlationId()).isEqualTo(correlationId);
        assertThat(dispatched.inReplyTo()).isEqualTo(5L);

        assertThat(response.isError()).isFalse();
        assertThat(text(response)).contains("closed");
    }

    @Test
    void done_selfCommit_fulfillsViaCommitmentServiceNoDispatch() {
        String agentId = "home-agent";
        String generatedCorrelationId = UUID.randomUUID().toString();

        Commitment created = commitment(generatedCorrelationId, null, agentId,
                Instant.now().plus(2, ChronoUnit.HOURS));
        when(commitmentService.open(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(created);

        // Commit and extract the generated commitmentId from the response
        ToolResponse commitResponse = tools.commit(agentId, "Run report", null, null);
        String commitmentId = extractField(text(commitResponse), "commitmentId");

        // done() — no channelId in commitment → CommitmentService.fulfill()
        when(commitmentStore.findByCorrelationId(anyString()))
                .thenReturn(Optional.of(created));
        when(commitmentService.fulfill(anyString())).thenReturn(Optional.of(created));

        ToolResponse response = tools.done(agentId, commitmentId, null);

        verify(messageService, never()).dispatch(any());
        verify(commitmentService).fulfill(anyString());
        assertThat(response.isError()).isFalse();
    }

    @Test
    void done_unknownCommitmentId_returnsError() {
        // Not in store
        when(commitmentStore.findByCorrelationId("unknown-id")).thenReturn(Optional.empty());

        ToolResponse response = tools.done("agent", "unknown-id", null);

        assertThat(response.isError()).isTrue();
        assertThat(text(response)).contains("COMMITMENT_NOT_FOUND");
    }

    @Test
    void done_channelBacked_commandMessageNotFound_returnsError() {
        UUID channelId = UUID.randomUUID();
        String agentId = "finance-agent";
        String correlationId = UUID.randomUUID().toString();

        when(commitmentStore.findByCorrelationId(correlationId))
                .thenReturn(Optional.of(commitment(correlationId, channelId, agentId,
                        Instant.now().plus(1, ChronoUnit.HOURS))));
        // No COMMAND in history
        when(messageService.findAllByCorrelationId(correlationId)).thenReturn(List.of());

        ToolResponse response = tools.done(agentId, correlationId, null);

        assertThat(response.isError()).isTrue();
        assertThat(text(response)).contains("COMMAND_NOT_FOUND");
    }

    // ---- resolveChannelId() behaviour — tested via done() ----

    @Test
    void resolveChannelId_nonTerminalWithChannelId_takesChannelBackedPath() {
        UUID channelId = UUID.randomUUID();
        String agentId = "agent";
        String correlationId = UUID.randomUUID().toString();

        when(commitmentStore.findByCorrelationId(correlationId))
                .thenReturn(Optional.of(commitment(correlationId, channelId, agentId,
                        Instant.now().plusSeconds(3600))));
        when(messageService.findAllByCorrelationId(correlationId))
                .thenReturn(List.of(message(5L, channelId, MessageType.COMMAND, correlationId)));
        when(messageService.dispatch(any()))
                .thenReturn(dispatchResult(11L, channelId, agentId, MessageType.DONE, correlationId));

        ToolResponse response = tools.done(agentId, correlationId, null);

        verify(messageService).dispatch(argThat(d -> MessageType.DONE == d.type()));
        assertThat(response.isError()).isFalse();
    }

    @Test
    void resolveChannelId_nonTerminalWithNullChannelId_takesSelfCommitPath() {
        String agentId = "agent";
        String correlationId = UUID.randomUUID().toString();
        Commitment c = commitment(correlationId, null, agentId, Instant.now().plusSeconds(3600));

        when(commitmentStore.findByCorrelationId(correlationId)).thenReturn(Optional.of(c));
        when(commitmentService.fulfill(anyString())).thenReturn(Optional.of(c));

        ToolResponse response = tools.done(agentId, correlationId, null);

        verify(commitmentService).fulfill(correlationId);
        verify(messageService, never()).dispatch(any());
        assertThat(response.isError()).isFalse();
    }

    @Test
    void resolveChannelId_terminalCommitment_returnsAlreadyClosed() {
        String agentId = "agent";
        String correlationId = UUID.randomUUID().toString();
        Commitment c = commitment(correlationId, UUID.randomUUID(), agentId, null);
        c.state = CommitmentState.FULFILLED;

        when(commitmentStore.findByCorrelationId(correlationId)).thenReturn(Optional.of(c));

        ToolResponse response = tools.done(agentId, correlationId, null);

        assertThat(response.isError()).isTrue();
        assertThat(text(response)).contains("COMMITMENT_ALREADY_CLOSED");
        verify(messageService, never()).dispatch(any());
    }

    @Test
    void resolveChannelId_notFound_returnsNotFound() {
        when(commitmentStore.findByCorrelationId("missing")).thenReturn(Optional.empty());

        ToolResponse response = tools.done("agent", "missing", null);

        assertThat(response.isError()).isTrue();
        assertThat(text(response)).contains("COMMITMENT_NOT_FOUND");
    }

    @Test
    void reject_selfCommit_declinesViaCommitmentService() {
        String agentId = "home-agent";
        Commitment created = commitment(UUID.randomUUID().toString(), null, agentId,
                Instant.now().plus(2, ChronoUnit.HOURS));
        when(commitmentService.open(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(created);
        ToolResponse commitResponse = tools.commit(agentId, "Run report", null, null);
        String commitmentId = extractField(text(commitResponse), "commitmentId");

        when(commitmentStore.findByCorrelationId(anyString()))
                .thenReturn(Optional.of(created));
        when(commitmentService.decline(anyString())).thenReturn(Optional.of(created));

        ToolResponse response = tools.reject(agentId, commitmentId, "Cannot complete");

        verify(messageService, never()).dispatch(any());
        verify(commitmentService).decline(anyString());
        assertThat(response.isError()).isFalse();
        assertThat(text(response)).contains("declined");
    }

    // ---- casehub_reject ----

    @Test
    void reject_dispatchesDeclineToChannel() {
        UUID channelId = UUID.randomUUID();
        String agentId = "finance-agent";
        String correlationId = UUID.randomUUID().toString();

        when(commitmentStore.findByCorrelationId(correlationId))
                .thenReturn(Optional.of(commitment(correlationId, channelId, agentId,
                        Instant.now().plusSeconds(3600))));
        when(messageService.findAllByCorrelationId(correlationId))
                .thenReturn(List.of(message(3L, channelId, MessageType.COMMAND, correlationId)));
        when(messageService.dispatch(any()))
                .thenReturn(dispatchResult(11L, channelId, agentId, MessageType.DECLINE, correlationId));

        ToolResponse response = tools.reject(agentId, correlationId, "Outside working hours");

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());
        assertThat(captor.getValue().type()).isEqualTo(MessageType.DECLINE);
        assertThat(captor.getValue().content()).isEqualTo("Outside working hours");
        assertThat(response.isError()).isFalse();
    }

    // ---- casehub_checkpoint ----

    @Test
    void checkpoint_dispatchesStatusWithNote() {
        UUID channelId = UUID.randomUUID();
        String agentId = "home-agent";
        String correlationId = UUID.randomUUID().toString();

        when(commitmentStore.findByCorrelationId(correlationId))
                .thenReturn(Optional.of(commitment(correlationId, channelId, agentId,
                        Instant.now().plusSeconds(3600))));
        when(messageService.dispatch(any()))
                .thenReturn(dispatchResult(11L, channelId, agentId, MessageType.STATUS, correlationId));

        ToolResponse response = tools.checkpoint(agentId, correlationId, "50% complete, on track");

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());
        assertThat(captor.getValue().type()).isEqualTo(MessageType.STATUS);
        assertThat(captor.getValue().content()).contains("50% complete");
        assertThat(response.isError()).isFalse();
    }

    @Test
    void checkpoint_unknownCommitmentId_returnsNotFound() {
        when(commitmentStore.findByCorrelationId("unknown")).thenReturn(Optional.empty());

        ToolResponse response = tools.checkpoint("agent", "unknown", "progress note");

        assertThat(response.isError()).isTrue();
        assertThat(text(response)).contains("COMMITMENT_NOT_FOUND");
        verify(messageService, never()).dispatch(any());
    }

    // ---- casehub_escalate ----

    @Test
    void escalate_dispatchesHandoffToChannel() {
        UUID channelId = UUID.randomUUID();
        String agentId = "finance-agent";
        String correlationId = UUID.randomUUID().toString();

        when(commitmentStore.findByCorrelationId(correlationId))
                .thenReturn(Optional.of(commitment(correlationId, channelId, agentId,
                        Instant.now().plusSeconds(3600))));
        when(messageService.findAllByCorrelationId(correlationId))
                .thenReturn(List.of(message(3L, channelId, MessageType.COMMAND, correlationId)));
        when(messageService.dispatch(any()))
                .thenReturn(dispatchResult(11L, channelId, agentId, MessageType.HANDOFF, correlationId));

        ToolResponse response = tools.escalate(agentId, correlationId, "Exceeds authority", "human-supervisor");

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());
        assertThat(captor.getValue().type()).isEqualTo(MessageType.HANDOFF);
        assertThat(captor.getValue().target()).isEqualTo("human-supervisor");
        assertThat(response.isError()).isFalse();

        // Escalated commitment is DELEGATED — done() sees terminal state → ALREADY_CLOSED
        when(commitmentStore.findByCorrelationId(correlationId))
                .thenReturn(Optional.of(delegatedCommitment(correlationId, channelId, agentId)));
        ToolResponse doneAfter = tools.done(agentId, correlationId, null);
        assertThat(doneAfter.isError()).isTrue();
        assertThat(text(doneAfter)).contains("COMMITMENT_ALREADY_CLOSED");
        // resolveChannelId() filters DELEGATED; selfCommit_done() finds it terminal → ALREADY_CLOSED
    }

    @Test
    void escalate_unknownCommitmentId_returnsNotFound() {
        when(commitmentStore.findByCorrelationId("unknown")).thenReturn(Optional.empty());

        ToolResponse response = tools.escalate("agent", "unknown", "reason", "target");

        assertThat(response.isError()).isTrue();
        assertThat(text(response)).contains("COMMITMENT_NOT_FOUND");
        verify(messageService, never()).dispatch(any());
    }

    // ---- post-escalation state guard ----

    @Test
    void done_afterEscalate_returnsAlreadyClosed() {
        UUID channelId = UUID.randomUUID();
        String agentId = "finance-agent";
        String correlationId = UUID.randomUUID().toString();

        when(commitmentStore.findByCorrelationId(correlationId))
                .thenReturn(Optional.of(commitment(correlationId, channelId, agentId,
                        Instant.now().plusSeconds(3600))));
        when(messageService.findAllByCorrelationId(correlationId))
                .thenReturn(List.of(message(5L, channelId, MessageType.COMMAND, correlationId)));
        when(messageService.dispatch(any()))
                .thenReturn(dispatchResult(11L, channelId, agentId, MessageType.HANDOFF, correlationId));

        ToolResponse escalateResult = tools.escalate(agentId, correlationId, "Exceeds authority", "supervisor");
        assertThat(escalateResult.isError()).isFalse();

        when(commitmentStore.findByCorrelationId(correlationId))
                .thenReturn(Optional.of(delegatedCommitment(correlationId, channelId, agentId)));

        ToolResponse doneResult = tools.done(agentId, correlationId, null);
        assertThat(doneResult.isError()).isTrue();
        assertThat(text(doneResult)).contains("COMMITMENT_ALREADY_CLOSED");
        verify(messageService, never()).dispatch(argThat(d -> MessageType.DONE == d.type()));
    }

    @Test
    void reject_afterEscalate_returnsAlreadyClosed() {
        UUID channelId = UUID.randomUUID();
        String agentId = "finance-agent";
        String correlationId = UUID.randomUUID().toString();

        when(commitmentStore.findByCorrelationId(correlationId))
                .thenReturn(Optional.of(commitment(correlationId, channelId, agentId,
                        Instant.now().plusSeconds(3600))));
        when(messageService.findAllByCorrelationId(correlationId))
                .thenReturn(List.of(message(5L, channelId, MessageType.COMMAND, correlationId)));
        when(messageService.dispatch(any()))
                .thenReturn(dispatchResult(11L, channelId, agentId, MessageType.HANDOFF, correlationId));

        ToolResponse escalateResult = tools.escalate(agentId, correlationId, "Exceeds authority", "supervisor");
        assertThat(escalateResult.isError()).isFalse();

        when(commitmentStore.findByCorrelationId(correlationId))
                .thenReturn(Optional.of(delegatedCommitment(correlationId, channelId, agentId)));

        ToolResponse rejectResult = tools.reject(agentId, correlationId, "Cannot complete");
        assertThat(rejectResult.isError()).isTrue();
        assertThat(text(rejectResult)).contains("COMMITMENT_ALREADY_CLOSED");
        verify(messageService, never()).dispatch(argThat(d -> MessageType.DECLINE == d.type()));
    }

    // ---- channel-only tools: terminal commitment returns NOT_FOUND, not ALREADY_CLOSED ----

    @Test
    void checkpoint_terminalCommitment_returnsNotFound_notAlreadyClosed() {
        // Known asymmetry (documented in spec): channel-only tools return COMMITMENT_NOT_FOUND
        // for terminal commitments (resolveChannelId returns empty); mixed-path tools (done/reject)
        // return COMMITMENT_ALREADY_CLOSED via selfCommit state guard.
        UUID channelId = UUID.randomUUID();
        String correlationId = UUID.randomUUID().toString();
        Commitment c = commitment(correlationId, channelId, "agent", null);
        c.state = CommitmentState.DELEGATED;

        when(commitmentStore.findByCorrelationId(correlationId)).thenReturn(Optional.of(c));

        ToolResponse response = tools.checkpoint("agent", correlationId, "progress note");

        assertThat(response.isError()).isTrue();
        assertThat(text(response)).contains("COMMITMENT_NOT_FOUND");
    }

    @Test
    void escalate_terminalCommitment_returnsNotFound_notAlreadyClosed() {
        UUID channelId = UUID.randomUUID();
        String correlationId = UUID.randomUUID().toString();
        Commitment c = commitment(correlationId, channelId, "agent", null);
        c.state = CommitmentState.DELEGATED;

        when(commitmentStore.findByCorrelationId(correlationId)).thenReturn(Optional.of(c));

        ToolResponse response = tools.escalate("agent", correlationId, "reason", "target");

        assertThat(response.isError()).isTrue();
        assertThat(text(response)).contains("COMMITMENT_NOT_FOUND");
    }

    @Test
    void delegate_terminalCommitment_returnsNotFound_notAlreadyClosed() {
        UUID channelId = UUID.randomUUID();
        String correlationId = UUID.randomUUID().toString();
        Commitment c = commitment(correlationId, channelId, "agent", null);
        c.state = CommitmentState.DELEGATED;

        when(commitmentStore.findByCorrelationId(correlationId)).thenReturn(Optional.of(c));

        ToolResponse response = tools.delegate("agent", correlationId, "reason", "target");

        assertThat(response.isError()).isTrue();
        assertThat(text(response)).contains("COMMITMENT_NOT_FOUND");
    }

    // ---- casehub_block ----

    @Test
    void block_channelBacked_updatesExpiresAtAndDispatchesBlockedStatus() {
        UUID channelId = UUID.randomUUID();
        String agentId = "finance-agent";
        String correlationId = UUID.randomUUID().toString();
        Instant originalDeadline = Instant.now().plus(1, ChronoUnit.HOURS);
        Instant blockedUntil = Instant.now().plus(24, ChronoUnit.HOURS);

        // block() setup
        Commitment c = commitment(correlationId, channelId, agentId, originalDeadline);
        when(commitmentStore.findByCorrelationId(correlationId)).thenReturn(Optional.of(c));
        when(commitmentStore.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(messageService.dispatch(any()))
                .thenReturn(dispatchResult(11L, channelId, agentId, MessageType.STATUS, correlationId));

        ToolResponse response = tools.block(agentId, correlationId, "Waiting for CFO sign-off", blockedUntil.toString());

        // save() called with updated expiresAt
        ArgumentCaptor<Commitment> saveCaptor = ArgumentCaptor.forClass(Commitment.class);
        verify(commitmentStore).save(saveCaptor.capture());
        assertThat(saveCaptor.getValue().expiresAt).isEqualTo(blockedUntil);
        assertThat(saveCaptor.getValue().expiresAt).isNotEqualTo(originalDeadline);

        // STATUS dispatched with "BLOCKED: " prefix
        ArgumentCaptor<MessageDispatch> dispatchCaptor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(dispatchCaptor.capture());
        MessageDispatch dispatched = dispatchCaptor.getValue();
        assertThat(dispatched.type()).isEqualTo(MessageType.STATUS);
        assertThat(dispatched.channelId()).isEqualTo(channelId);
        assertThat(dispatched.content()).startsWith("BLOCKED: ");
        assertThat(dispatched.content()).contains("CFO sign-off");

        assertThat(response.isError()).isFalse();
        assertThat(text(response)).contains("blocked");
        assertThat(text(response)).contains("newWatchdogDeadline");
    }

    @Test
    void block_selfCommit_updatesExpiresAtWithoutDispatch() {
        String agentId = "home-agent";
        String correlationId = UUID.randomUUID().toString();
        Instant blockedUntil = Instant.now().plus(4, ChronoUnit.HOURS);

        // No channelId — self-commit scenario
        Commitment c = commitment(correlationId, null, agentId, Instant.now().plus(1, ChronoUnit.HOURS));
        when(commitmentStore.findByCorrelationId(correlationId)).thenReturn(Optional.of(c));
        when(commitmentStore.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ToolResponse response = tools.block(agentId, correlationId, "Waiting for delivery", blockedUntil.toString());

        ArgumentCaptor<Commitment> saveCaptor = ArgumentCaptor.forClass(Commitment.class);
        verify(commitmentStore).save(saveCaptor.capture());
        assertThat(saveCaptor.getValue().expiresAt).isEqualTo(blockedUntil);
        verify(messageService, never()).dispatch(any());
        assertThat(response.isError()).isFalse();
    }

    @Test
    void block_unknownCommitmentId_returnsNotFound() {
        when(commitmentStore.findByCorrelationId("unknown")).thenReturn(Optional.empty());

        ToolResponse response = tools.block("agent", "unknown", "reason",
                Instant.now().plus(1, ChronoUnit.HOURS).toString());

        assertThat(response.isError()).isTrue();
        assertThat(text(response)).contains("COMMITMENT_NOT_FOUND");
        verify(commitmentStore, never()).save(any());
    }

    @Test
    void block_terminalCommitment_returnsAlreadyClosed() {
        String agentId = "agent";
        String correlationId = UUID.randomUUID().toString();
        Commitment c = commitment(correlationId, null, agentId, Instant.now().plus(1, ChronoUnit.HOURS));
        c.state = CommitmentState.FULFILLED;
        when(commitmentStore.findByCorrelationId(correlationId)).thenReturn(Optional.of(c));

        ToolResponse response = tools.block(agentId, correlationId, "reason",
                Instant.now().plus(1, ChronoUnit.HOURS).toString());

        assertThat(response.isError()).isTrue();
        assertThat(text(response)).contains("COMMITMENT_ALREADY_CLOSED");
        verify(commitmentStore, never()).save(any());
    }

    @Test
    void block_wrongObligor_returnsUnauthorized() {
        String realObligor = "finance-agent";
        String wrongAgent = "intruder-agent";
        String correlationId = UUID.randomUUID().toString();
        Commitment c = commitment(correlationId, null, realObligor, Instant.now().plus(1, ChronoUnit.HOURS));
        when(commitmentStore.findByCorrelationId(correlationId)).thenReturn(Optional.of(c));

        ToolResponse response = tools.block(wrongAgent, correlationId, "reason",
                Instant.now().plus(1, ChronoUnit.HOURS).toString());

        assertThat(response.isError()).isTrue();
        assertThat(text(response)).contains("COMMITMENT_UNAUTHORIZED");
        verify(commitmentStore, never()).save(any());
    }

    @Test
    void block_invalidDeadlineFormat_returnsInvalidDeadline() {
        ToolResponse response = tools.block("agent", "any-id", "reason", "not-a-timestamp");

        assertThat(response.isError()).isTrue();
        assertThat(text(response)).contains("INVALID_DEADLINE");
    }

    @Test
    void block_deadlineInPast_returnsDeadlineInPast() {
        String pastTimestamp = Instant.now().minus(1, ChronoUnit.HOURS).toString();
        ToolResponse response = tools.block("agent", "any-id", "reason", pastTimestamp);

        assertThat(response.isError()).isTrue();
        assertThat(text(response)).contains("DEADLINE_IN_PAST");
        verify(commitmentStore, never()).findByCorrelationId(any());
        verify(commitmentStore, never()).save(any());
    }

    @Test
    void block_dispatchThrowsAfterSave_propagatesException() {
        UUID channelId = UUID.randomUUID();
        String agentId = "finance-agent";
        String correlationId = UUID.randomUUID().toString();

        Commitment c = commitment(correlationId, channelId, agentId, Instant.now().plus(1, ChronoUnit.HOURS));
        when(commitmentStore.findByCorrelationId(correlationId)).thenReturn(Optional.of(c));
        when(commitmentStore.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(messageService.dispatch(any())).thenThrow(new RuntimeException("channel unavailable"));

        // Propagation (not silent swallow) is the correct policy: @Transactional on block()
        // means the JTA container rolls back the save() when the exception unwinds.
        // Unit tests can't verify JTA rollback; this confirms the exception is not caught and swallowed.
        assertThatThrownBy(() -> tools.block(agentId, correlationId, "blocked",
                Instant.now().plus(2, ChronoUnit.HOURS).toString()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("channel unavailable");
    }

    // ---- casehub_delegate ----

    @Test
    void delegate_dispatchesHandoffWithReasonAsContentAndRequiredToAgent() {
        UUID channelId = UUID.randomUUID();
        String agentId = "finance-agent";
        String correlationId = UUID.randomUUID().toString();

        when(commitmentStore.findByCorrelationId(correlationId))
                .thenReturn(Optional.of(commitment(correlationId, channelId, agentId,
                        Instant.now().plus(1, ChronoUnit.HOURS))));

        // delegate() looks up COMMAND message for inReplyTo
        when(messageService.findAllByCorrelationId(correlationId))
                .thenReturn(List.of(message(5L, channelId, MessageType.COMMAND, correlationId)));
        when(messageService.dispatch(any()))
                .thenReturn(dispatchResult(11L, channelId, agentId, MessageType.HANDOFF, correlationId));

        ToolResponse response = tools.delegate(agentId, correlationId,
                "Delegating to specialist", "tax-specialist-agent");

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());
        MessageDispatch dispatched = captor.getValue();
        assertThat(dispatched.type()).isEqualTo(MessageType.HANDOFF);
        assertThat(dispatched.channelId()).isEqualTo(channelId);
        assertThat(dispatched.correlationId()).isEqualTo(correlationId);
        assertThat(dispatched.inReplyTo()).isEqualTo(5L);
        assertThat(dispatched.target()).isEqualTo("tax-specialist-agent");
        assertThat(dispatched.content()).isEqualTo("Delegating to specialist");

        assertThat(response.isError()).isFalse();
        assertThat(text(response)).contains("delegated");
        assertThat(text(response)).contains("tax-specialist-agent");
    }

    @Test
    void delegate_noChannelMapEntry_returnsNotFound() {
        // No commitment in store — resolveChannelId() returns empty → COMMITMENT_NOT_FOUND
        when(commitmentStore.findByCorrelationId("unknown-id")).thenReturn(Optional.empty());

        ToolResponse response = tools.delegate("agent", "unknown-id",
                "reason", "target-agent");

        assertThat(response.isError()).isTrue();
        assertThat(text(response)).contains("COMMITMENT_NOT_FOUND");
        verify(messageService, never()).dispatch(any());
    }

    @Test
    void delegate_commandMessageNotFound_returnsCommandNotFound() {
        UUID channelId = UUID.randomUUID();
        String agentId = "finance-agent";
        String correlationId = UUID.randomUUID().toString();

        when(commitmentStore.findByCorrelationId(correlationId))
                .thenReturn(Optional.of(commitment(correlationId, channelId, agentId,
                        Instant.now().plus(1, ChronoUnit.HOURS))));

        // No COMMAND in history
        when(messageService.findAllByCorrelationId(correlationId)).thenReturn(List.of());

        ToolResponse response = tools.delegate(agentId, correlationId,
                "Delegating to specialist", "tax-specialist-agent");

        assertThat(response.isError()).isTrue();
        assertThat(text(response)).contains("COMMAND_NOT_FOUND");
        verify(messageService, never()).dispatch(any());
    }

    @Test
    void delegate_dispatchesHandoffToChannel() {
        UUID channelId = UUID.randomUUID();
        String agentId = "finance-agent";
        String correlationId = UUID.randomUUID().toString();

        when(commitmentStore.findByCorrelationId(correlationId))
                .thenReturn(Optional.of(commitment(correlationId, channelId, agentId,
                        Instant.now().plus(1, ChronoUnit.HOURS))));

        when(messageService.findAllByCorrelationId(correlationId))
                .thenReturn(List.of(message(5L, channelId, MessageType.COMMAND, correlationId)));
        when(messageService.dispatch(any()))
                .thenReturn(dispatchResult(11L, channelId, agentId, MessageType.HANDOFF, correlationId));
        tools.delegate(agentId, correlationId, "Delegating", "other-agent");

        // After delegate, commitment is DELEGATED — done() sees terminal → ALREADY_CLOSED
        when(commitmentStore.findByCorrelationId(correlationId))
                .thenReturn(Optional.of(delegatedCommitment(correlationId, channelId, agentId)));
        ToolResponse doneAfter = tools.done(agentId, correlationId, null);
        assertThat(doneAfter.isError()).isTrue();
        assertThat(text(doneAfter)).contains("COMMITMENT_ALREADY_CLOSED");
    }

    // ---- helpers ----

    private static Commitment commitment(String correlationId, UUID channelId,
                                         String obligor, Instant expiresAt) {
        Commitment c = new Commitment();
        c.id = UUID.randomUUID();
        c.correlationId = correlationId;
        c.channelId = channelId;
        c.obligor = obligor;
        c.state = CommitmentState.OPEN;
        c.expiresAt = expiresAt;
        return c;
    }

    private static Commitment delegatedCommitment(String correlationId, UUID channelId, String obligor) {
        Commitment c = commitment(correlationId, channelId, obligor, null);
        c.state = CommitmentState.DELEGATED;
        return c;
    }

    private static Message message(long id, UUID channelId, MessageType type, String correlationId) {
        Message m = new Message();
        m.id = id;
        m.channelId = channelId;
        m.messageType = type;
        m.correlationId = correlationId;
        return m;
    }

    private static DispatchResult dispatchResult(long messageId, UUID channelId, String sender,
                                                  MessageType type, String correlationId) {
        return new DispatchResult(messageId, channelId, sender, type, correlationId,
                null, List.of(), null, null, null, null, 0);
    }

    private static String text(ToolResponse response) {
        return response.content().stream()
                .filter(c -> c instanceof io.quarkiverse.mcp.server.TextContent)
                .map(c -> ((io.quarkiverse.mcp.server.TextContent) c).text())
                .findFirst()
                .orElse("");
    }

    /** Extract a quoted JSON string value by key — enough for test assertions. */
    private static String extractField(String json, String key) {
        int idx = json.indexOf("\"" + key + "\"");
        if (idx < 0) return "";
        int colon = json.indexOf(':', idx);
        int start = json.indexOf('"', colon) + 1;
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }
}
