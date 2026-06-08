package io.casehub.openclaw.app;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.openclaw.casehub.OversightGateService;
import io.casehub.openclaw.client.OpenClawGatewayClient;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.message.Message;
import io.casehub.qhorus.runtime.message.MessageService;
import io.casehub.qhorus.runtime.store.query.MessageQuery;
import io.casehub.qhorus.testing.InMemoryCommitmentStore;
import io.casehub.qhorus.testing.InMemoryMessageStore;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies CDI wiring and fail-open behaviour of OversightGateService.fulfill() when
 * OversightGateDispatcher.dispatch() throws on the second MessageService.dispatch() call.
 *
 * <p>What this test proves:
 * <ul>
 *   <li>@Transactional is container-resolved (CDI wiring is correct)</li>
 *   <li>fulfill() is fail-open: a dispatch failure inside gateDispatcher does not propagate</li>
 *   <li>The work channel receives no STATUS from GATE_SENDER when the second dispatch fails</li>
 * </ul>
 *
 * <p>What this test cannot prove: JTA rollback of the first dispatch. InMemoryMessageStore
 * (casehub-qhorus-testing) uses a ConcurrentHashMap — writes are immediate and are not
 * rolled back when the JPA transaction rolls back. The oversight channel RESPONSE from the
 * first dispatch remains in the store. If the store is ever replaced with a JPA-backed
 * store, add: assertThat(oversightResponse).isEmpty() to verify the RESPONSE was rolled back.
 *
 * <p>Note: OpenClawGatewayClient (@RestClient interface) is mocked to satisfy CDI wiring.
 * fulfill() itself never calls the hook client — the mock prevents startup failure caused
 * by the missing gateway URL in the test environment.
 */
@QuarkusTest
class OversightGateDispatcherCdiTest {

    @Inject
    OversightGateService oversightGateService;

    @Inject
    ChannelService channelService;

    @Inject
    InMemoryMessageStore messageStore;

    @Inject
    InMemoryCommitmentStore commitmentStore;

    @InjectSpy
    MessageService messageService;

    @InjectMock
    @RestClient
    OpenClawGatewayClient gatewayClient;   // CDI wiring — fulfill() never calls it, but
                                            // Quarkus wires all injected beans at context
                                            // startup; without this mock, startup fails

    UUID caseId;
    UUID gateId;
    Channel oversightChannel;
    Channel workChannel;

    @BeforeEach
    void setUp() {
        // Clear InMemory stores so no prior test's data leaks into this test.
        // UUID-per-test isolation prevents cross-test pollution today (one test method),
        // but store growth is unbounded without this; latent bug for any future test added.
        // Injected by concrete type (InMemory*) because clear() is not on the store interfaces.
        messageStore.clear();
        commitmentStore.clear();

        caseId = UUID.randomUUID();
        gateId = UUID.randomUUID();

        // Channel names must match CaseChannelNames exactly — fulfill() looks them up by name
        oversightChannel = channelService.create(
                "case-" + caseId + "/oversight", "Oversight", ChannelSemantic.APPEND, null);
        workChannel = channelService.create(
                "case-" + caseId + "/work", "Work", ChannelSemantic.APPEND, null);

        // Default gateway mock: return 200 for all invocations. OpenClawHookClient is an
        // @ApplicationScoped CDI bean in the test context; the gateway client it injects
        // must be stubbed to avoid NPE when other tests in the same context invoke agents.
        when(gatewayClient.invokeAgent(any())).thenReturn(Response.ok().build());

        // Bridge findAllByCorrelationId to InMemory store.
        // fulfill() calls messageService.findAllByCorrelationId(gateId) to find the COMMAND
        // message. Without this bridge, Panache queries H2 and finds nothing (test writes
        // to InMemoryMessageStore, not H2).
        doAnswer(invocation -> {
            String correlationId = invocation.getArgument(0);
            return messageStore.scan(MessageQuery.builder().build()).stream()
                    .filter(m -> correlationId.equals(m.correlationId))
                    .sorted(Comparator.comparingLong(m -> m.id))
                    .toList();
        }).when(messageService).findAllByCorrelationId(any());
    }

    @Test
    void second_dispatch_failure_leaves_work_channel_empty_and_fulfill_is_fail_open() {
        // 1. Dispatch setup COMMAND to oversight channel.
        //    This simulates the gate COMMAND that OversightGateService.openGate() used to
        //    dispatch (openclaw#30 will re-wire the gate entry). Qhorus InMemory auto-creates
        //    a Commitment with channelId=oversightChannelId and correlationId=gateId —
        //    this is what fulfill() looks up.
        messageService.dispatch(MessageDispatch.builder()
                .channelId(oversightChannel.id)
                .sender("openclaw-gate")   // matches OversightGateService.GATE_SENDER (package-private)
                .type(MessageType.COMMAND)
                .content("proposed action")
                .correlationId(gateId.toString())
                .actorType(ActorType.AGENT)
                .build());

        // 2. Reset Mockito invocation count so that verify(times(2)) counts only
        //    the two dispatches triggered by fulfill(), not the setup COMMAND.
        clearInvocations(messageService);

        // 3. Stub dispatch(): first call real (RESPONSE to oversight),
        //    second call throws (STATUS to work — never written).
        // Steps 2 and 3 are order-dependent: clearInvocations() resets the spy's
        // invocation history but does NOT clear stubbing. The stub must be set
        // *after* clearInvocations() so that the call-order tracking (first real,
        // second throw) begins from a clean baseline — not from the setup COMMAND.
        doCallRealMethod()
                .doThrow(new RuntimeException("simulated second-dispatch failure"))
                .when(messageService).dispatch(any());

        // 4. Trigger fulfill — this calls gateDispatcher.dispatch() which makes both
        //    dispatch calls. The second throws; fulfill() catches it (fail-open).
        oversightGateService.fulfill(gateId, "approved");

        // Assertion 1: fulfill() returned without throwing (test would have failed above
        // if an exception had propagated).

        // Assertion 2: Work channel has no STATUS from GATE_SENDER.
        //   The second dispatch (STATUS) threw before any write — verifiable even with InMemory.
        List<Message> workStatus = messageStore.scan(MessageQuery.builder().build()).stream()
                .filter(m -> workChannel.id.equals(m.channelId)
                          && MessageType.STATUS == m.messageType
                          && "openclaw-gate".equals(m.sender))   // matches OversightGateService.GATE_SENDER (package-private)
                .toList();
        assertThat(workStatus).isEmpty();

        // Assertion 3: Exactly two fulfill-path dispatch calls attempted
        //   (RESPONSE succeeded, STATUS threw — both count as invocations in Mockito).
        verify(messageService, times(2)).dispatch(any());
    }
}
