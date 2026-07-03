package io.casehub.openclaw.app;

import java.io.StringWriter;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.openclaw.casehub.OversightGateService;
import io.casehub.openclaw.client.OpenClawGatewayClient;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.channel.ChannelCreateRequest;
import io.casehub.qhorus.api.message.Message;
import io.casehub.qhorus.api.store.query.MessageQuery;
import io.casehub.qhorus.persistence.memory.InMemoryCommitmentStore;
import io.casehub.qhorus.persistence.memory.InMemoryMessageStore;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.message.MessageService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
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
 *   <li>The work channel receives no DONE from GATE_SENDER when the second dispatch fails</li>
 * </ul>
 *
 * <p>Gate context is written in Properties format (as OversightGateService.serializeGateContent()
 * produces) so that fulfill() can parse tenancyId, workChannelId, and commandMessageId from
 * the stored COMMAND message. The CrossTenantMessageStore in the test context is backed by
 * the same InMemoryMessageStore (InMemoryCrossTenantMessageStore delegates to it), so a
 * message dispatched via messageService.dispatch() is immediately visible to fulfill().
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

        // Channel names follow CaseChannel.channelName(caseId, purpose) convention
        oversightChannel = channelService.create(
                ChannelCreateRequest.builder("case-" + caseId + "/oversight").description("Oversight").semantic(ChannelSemantic.APPEND).build());
        workChannel = channelService.create(
                ChannelCreateRequest.builder("case-" + caseId + "/work").description("Work").semantic(ChannelSemantic.APPEND).build());

        // Default gateway mock: return 200 for all invocations. OpenClawHookClient is an
        // @ApplicationScoped CDI bean in the test context; the gateway client it injects
        // must be stubbed to avoid NPE when other tests in the same context invoke agents.
        when(gatewayClient.invokeAgent(any())).thenReturn(Response.ok().build());
    }

    @Test
    void second_dispatch_failure_leaves_work_channel_no_done_and_fulfill_is_fail_open() {
        // 1. Build gate content with tenancyId and workChannelId in Properties format —
        //    same format that OversightGateService.serializeGateContent() produces.
        //    fulfill() will parse this to extract workChannelId, commandMessageId, and tenancyId.
        String commitmentId = UUID.randomUUID().toString();
        Properties props = new Properties();
        props.setProperty("originalCommitmentId", commitmentId);
        props.setProperty("workChannelId", workChannel.id().toString());
        props.setProperty("commandMessageId", "99");
        props.setProperty("reason", "risk: test action");
        props.setProperty("tenancyId", TenancyConstants.DEFAULT_TENANT_ID);  // FixedCurrentPrincipal default
        StringWriter sw = new StringWriter();
        try {
            props.store(sw, null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // 2. Dispatch COMMAND with Properties-format gate content to oversight channel.
        //    CrossTenantMessageStore (backed by InMemoryMessageStore) makes it visible to
        //    fulfill()'s crossTenantMessageStore.scan() lookup.
        messageService.dispatch(MessageDispatch.builder()
                .channelId(oversightChannel.id())
                .sender("openclaw-gate")
                .type(MessageType.COMMAND)
                .content(sw.toString())
                .correlationId(gateId.toString())
                .actorType(ActorType.AGENT)
                .build());

        // 3. Reset Mockito invocation count so that verify(times(2)) counts only
        //    the two dispatches triggered by fulfill(), not the setup COMMAND.
        clearInvocations(messageService);

        // 4. Stub dispatch(): first call real (RESPONSE to oversight channel),
        //    second call throws (DONE to work channel — never written).
        //    Steps 3 and 4 are order-dependent: clearInvocations() resets the spy's
        //    invocation history but does NOT clear stubbing. The stub must be set
        //    *after* clearInvocations() so that call-order tracking (first real,
        //    second throw) begins from a clean baseline.
        doCallRealMethod()
                .doThrow(new RuntimeException("simulated second-dispatch failure"))
                .when(messageService).dispatch(any());

        // 5. fulfill() — should not throw (fail-open contract on OversightGateService).
        oversightGateService.fulfill(gateId, "approved");

        // Assertion 1: fulfill() returned without throwing (test would have failed above
        // if an exception had propagated).

        // Assertion 2: Work channel has no DONE from GATE_SENDER.
        //   The second dispatch (DONE) threw before any write — verifiable even with InMemory.
        List<Message> workDone = messageStore.scan(MessageQuery.builder().build()).stream()
                .filter(m -> workChannel.id().equals(m.channelId())
                          && MessageType.DONE == m.messageType()
                          && "openclaw-gate".equals(m.sender()))
                .toList();
        assertThat(workDone).isEmpty();

        // Assertion 3: Exactly two fulfill-path dispatch calls attempted
        //   (RESPONSE succeeded, DONE threw — both count as invocations in Mockito).
        verify(messageService, times(2)).dispatch(any());
    }
}
