package io.casehub.openclaw.casehub;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.api.model.CaseChannel;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.message.DispatchResult;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.channel.ReactiveChannelService;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.casehub.qhorus.runtime.message.ReactiveMessageService;
import io.casehub.openclaw.context.ChannelContextWindowService;
import io.smallrye.mutiny.Uni;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReactiveOpenClawCaseChannelProviderTest {

    ReactiveChannelService channelService;
    ReactiveMessageService messageService;
    ChannelContextWindowService contextService;
    ChannelGateway gateway;
    ReactiveOpenClawCaseChannelProvider provider;

    @BeforeEach
    void setUp() {
        channelService = mock(ReactiveChannelService.class);
        messageService = mock(ReactiveMessageService.class);
        contextService = mock(ChannelContextWindowService.class);
        gateway = mock(ChannelGateway.class);
        provider = new ReactiveOpenClawCaseChannelProvider(
                channelService, messageService, contextService, gateway);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Channel channel(UUID id, String name) {
        Channel ch = new Channel();
        ch.id = id;
        ch.name = name;
        return ch;
    }

    /** Stubs findByName → empty, create → new channel, for any channel name containing caseId. */
    private void stubCreate(UUID caseId) {
        when(channelService.findByName(anyString()))
                .thenReturn(Uni.createFrom().item(Optional.empty()));
        when(channelService.create(
                contains(caseId.toString()), any(), any(),
                isNull(), isNull(), isNull(), isNull(), isNull(), any(), any()))
                .thenAnswer(inv -> {
                    String name = inv.getArgument(0);
                    return Uni.createFrom().item(channel(UUID.randomUUID(), name));
                });
    }

    private DispatchResult dr(UUID channelId) {
        return new DispatchResult(1L, channelId, "sender", MessageType.STATUS,
                null, null, List.of(), null, null, null, null, 0);
    }

    // ── openChannel — create path ─────────────────────────────────────────────

    @Test
    void openChannel_newChannel_returnsCorrectCaseChannel() {
        UUID caseId = UUID.randomUUID();
        stubCreate(caseId);

        CaseChannel result = provider.openChannel(caseId, "work").await().indefinitely();

        assertThat(result).isNotNull();
        assertThat(result.purpose()).isEqualTo("work");
        assertThat(result.backendType()).isEqualTo("qhorus");
        assertThat(result.properties()).containsKey("qhorus-name");
    }

    @Test
    void openChannel_initializesAllThreeLayoutChannels() {
        UUID caseId = UUID.randomUUID();
        stubCreate(caseId);

        provider.openChannel(caseId, "work").await().indefinitely();

        // layout has 3 channels: work, observe, oversight — all created on first touch
        verify(channelService, times(3)).create(
                anyString(), any(), any(),
                isNull(), isNull(), isNull(), isNull(), isNull(), any(), any());
    }

    @Test
    void openChannel_newChannel_callsInitChannelOnEachCreatedChannel() {
        UUID caseId = UUID.randomUUID();
        stubCreate(caseId);

        provider.openChannel(caseId, "work").await().indefinitely();

        verify(gateway, times(3)).initChannel(any(UUID.class), any(ChannelRef.class));
    }

    @Test
    void openChannel_initChannelCalledWithCorrectChannelName() {
        UUID caseId = UUID.randomUUID();
        stubCreate(caseId);

        provider.openChannel(caseId, "work").await().indefinitely();

        verify(gateway).initChannel(
                any(UUID.class),
                argThat(ref -> ref.name().equals("case-" + caseId + "/work")));
    }

    @Test
    void openChannel_callsBindChannelForEachChannel() {
        UUID caseId = UUID.randomUUID();
        stubCreate(caseId);

        provider.openChannel(caseId, "work").await().indefinitely();

        verify(contextService, times(3)).bindChannel(eq(caseId), any(UUID.class));
    }

    @Test
    void openChannel_channelIdIsStringified() {
        UUID caseId = UUID.randomUUID();
        UUID channelId = UUID.randomUUID();
        when(channelService.findByName(anyString()))
                .thenReturn(Uni.createFrom().item(Optional.empty()));
        when(channelService.create(eq("case-" + caseId + "/work"), any(), any(),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(Uni.createFrom().item(channel(channelId, "case-" + caseId + "/work")));
        when(channelService.create(contains("/observe"), any(), any(),
                isNull(), isNull(), isNull(), isNull(), isNull(), any(), any()))
                .thenAnswer(inv -> Uni.createFrom().item(channel(UUID.randomUUID(), inv.getArgument(0))));
        when(channelService.create(contains("/oversight"), any(), any(),
                isNull(), isNull(), isNull(), isNull(), isNull(), any(), any()))
                .thenAnswer(inv -> Uni.createFrom().item(channel(UUID.randomUUID(), inv.getArgument(0))));

        CaseChannel result = provider.openChannel(caseId, "work").await().indefinitely();

        assertThat(result.id()).isEqualTo(channelId.toString());
    }

    // ── openChannel — layout type constraints ─────────────────────────────────

    @Test
    void openChannel_workChannel_bothTypesNull() {
        UUID caseId = UUID.randomUUID();
        stubCreate(caseId);

        provider.openChannel(caseId, "work").await().indefinitely();

        verify(channelService).create(
                eq("case-" + caseId + "/work"), anyString(), any(),
                isNull(), isNull(), isNull(), isNull(), isNull(),
                isNull(), isNull());
    }

    @Test
    void openChannel_observeChannel_allowedTypesEventDeniedNull() {
        UUID caseId = UUID.randomUUID();
        stubCreate(caseId);

        provider.openChannel(caseId, "observe").await().indefinitely();

        verify(channelService).create(
                contains("/observe"), anyString(), any(),
                isNull(), isNull(), isNull(), isNull(), isNull(),
                eq("EVENT"),  // allowedTypes
                isNull());    // deniedTypes
    }

    @Test
    void openChannel_oversightChannel_deniedTypesEventAllowedNull() {
        UUID caseId = UUID.randomUUID();
        stubCreate(caseId);

        provider.openChannel(caseId, "oversight").await().indefinitely();

        verify(channelService).create(
                contains("/oversight"), anyString(), any(),
                isNull(), isNull(), isNull(), isNull(), isNull(),
                isNull(),        // allowedTypes
                eq("EVENT"));    // deniedTypes
    }

    // ── openChannel — cache ───────────────────────────────────────────────────

    @Test
    void openChannel_secondCallSameCaseId_returnsFromCache() {
        UUID caseId = UUID.randomUUID();
        stubCreate(caseId);

        provider.openChannel(caseId, "work").await().indefinitely();
        CaseChannel observe = provider.openChannel(caseId, "observe").await().indefinitely();

        // Still only 3 creates — second call hits the layout cache
        verify(channelService, times(3)).create(
                anyString(), any(), any(),
                isNull(), isNull(), isNull(), isNull(), isNull(), any(), any());
        assertThat(observe.purpose()).isEqualTo("observe");
    }

    @Test
    void openChannel_differentCaseIds_initializeSeparately() {
        UUID caseId1 = UUID.randomUUID();
        UUID caseId2 = UUID.randomUUID();
        stubCreate(caseId1);
        stubCreate(caseId2);

        provider.openChannel(caseId1, "work").await().indefinitely();
        provider.openChannel(caseId2, "work").await().indefinitely();

        verify(channelService, times(6)).create(
                anyString(), any(), any(),
                isNull(), isNull(), isNull(), isNull(), isNull(), any(), any());
    }

    @Test
    void openChannel_concurrentCallsSameCaseId_initializesOnlyOnce() throws InterruptedException {
        UUID caseId = UUID.randomUUID();
        stubCreate(caseId);

        int threads = 3;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        CaseChannel[] results = new CaseChannel[threads];
        Throwable[] errors = new Throwable[threads];
        String[] purposes = {"work", "observe", "work"};

        for (int i = 0; i < threads; i++) {
            int idx = i;
            new Thread(() -> {
                try {
                    ready.countDown();
                    go.await();
                    provider.openChannel(caseId, purposes[idx])
                            .subscribe().with(
                                    ch -> { results[idx] = ch; done.countDown(); },
                                    err -> { errors[idx] = err; done.countDown(); });
                } catch (Exception e) {
                    errors[idx] = e;
                    done.countDown();
                }
            }).start();
        }

        ready.await();
        go.countDown();
        done.await();

        for (int i = 0; i < threads; i++) {
            assertThat(errors[i]).as("thread " + i + " error").isNull();
            assertThat(results[i]).as("thread " + i + " result").isNotNull();
            assertThat(results[i].purpose()).isEqualTo(purposes[i]);
        }
        // 3 layout channels — should create exactly 3, not 9
        verify(channelService, times(3)).create(
                anyString(), any(), any(),
                isNull(), isNull(), isNull(), isNull(), isNull(), any(), any());
    }

    @Test
    void openChannel_existingChannels_doesNotCallCreate_doesNotCallInitChannel() {
        UUID caseId = UUID.randomUUID();
        UUID channelId = UUID.randomUUID();
        // All channels already exist
        when(channelService.findByName(anyString()))
                .thenAnswer(inv -> {
                    String name = inv.getArgument(0);
                    return Uni.createFrom().item(Optional.of(channel(UUID.randomUUID(), name)));
                });

        provider.openChannel(caseId, "work").await().indefinitely();

        verify(channelService, never()).create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(gateway, never()).initChannel(any(UUID.class), any(ChannelRef.class));
    }

    @Test
    void openChannel_unknownPurpose_emitsIllegalArgumentException() {
        UUID caseId = UUID.randomUUID();
        stubCreate(caseId);

        assertThatThrownBy(() ->
                provider.openChannel(caseId, "unknown-purpose").await().indefinitely())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown-purpose");
    }

    @Test
    void openChannel_failedLayout_retriesOnNextCall() {
        UUID caseId = UUID.randomUUID();
        // First attempt: create fails
        when(channelService.findByName(anyString()))
                .thenReturn(Uni.createFrom().item(Optional.empty()));
        when(channelService.create(anyString(), any(), any(),
                isNull(), isNull(), isNull(), isNull(), isNull(), any(), any()))
                .thenReturn(Uni.createFrom().failure(new RuntimeException("transient")));

        assertThatThrownBy(() -> provider.openChannel(caseId, "work").await().indefinitely())
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("transient");

        // Second attempt: create succeeds
        stubCreate(caseId);

        CaseChannel result = provider.openChannel(caseId, "work").await().indefinitely();

        assertThat(result.purpose()).isEqualTo("work");
    }

    // ── postToChannel ─────────────────────────────────────────────────────────

    @Test
    void postToChannel_dispatchesWithCorrectFields() {
        UUID channelId = UUID.randomUUID();
        CaseChannel ch = new CaseChannel(channelId.toString(), "case-x/work", "work", "qhorus", Map.of());
        when(messageService.dispatch(any(MessageDispatch.class)))
                .thenReturn(Uni.createFrom().item(dr(channelId)));

        provider.postToChannel(ch, "engine", "content", MessageType.COMMAND, "corr-1", null)
                .await().indefinitely();

        verify(messageService).dispatch(argThat(d ->
                channelId.equals(d.channelId()) &&
                "engine".equals(d.sender()) &&
                d.type() == MessageType.COMMAND &&
                "content".equals(d.content()) &&
                "corr-1".equals(d.correlationId()) &&
                d.deadline() == null &&
                d.actorType() == ActorType.AGENT));
    }

    @Test
    void postToChannel_nullType_defaultsToStatus() {
        UUID channelId = UUID.randomUUID();
        CaseChannel ch = new CaseChannel(channelId.toString(), "case-x/work", "work", "qhorus", Map.of());
        when(messageService.dispatch(any(MessageDispatch.class)))
                .thenReturn(Uni.createFrom().item(dr(channelId)));

        provider.postToChannel(ch, "engine", "content", null, null, null)
                .await().indefinitely();

        verify(messageService).dispatch(argThat(d -> d.type() == MessageType.STATUS));
    }

    @Test
    void postToChannel_withDeadline_parsesAndPassesInstant() {
        UUID channelId = UUID.randomUUID();
        CaseChannel ch = new CaseChannel(channelId.toString(), "case-x/work", "work", "qhorus", Map.of());
        String deadline = "2026-05-23T12:00:00Z";
        when(messageService.dispatch(any(MessageDispatch.class)))
                .thenReturn(Uni.createFrom().item(dr(channelId)));

        provider.postToChannel(ch, "engine", "{}", MessageType.COMMAND, "42", deadline)
                .await().indefinitely();

        verify(messageService).dispatch(argThat(d ->
                Instant.parse(deadline).equals(d.deadline())));
    }

    @Test
    void postToChannel_threeArgDefault_callsDispatch() {
        UUID channelId = UUID.randomUUID();
        CaseChannel ch = new CaseChannel(channelId.toString(), "case-x/work", "work", "qhorus", Map.of());
        when(messageService.dispatch(any(MessageDispatch.class)))
                .thenReturn(Uni.createFrom().item(dr(channelId)));

        provider.postToChannel(ch, "sender", "hello").await().indefinitely();

        verify(messageService).dispatch(any(MessageDispatch.class));
    }

    // ── closeChannel ──────────────────────────────────────────────────────────

    @Test
    void closeChannel_completesWithVoid_noInteractions() {
        CaseChannel ch = new CaseChannel("ch-id", "case-x/work", "work", "qhorus", Map.of());

        Void result = provider.closeChannel(ch).await().indefinitely();

        assertThat(result).isNull();
        verifyNoInteractions(channelService, messageService, gateway);
    }

    // ── listChannels ──────────────────────────────────────────────────────────

    @Test
    void listChannels_delegatesToFindByNamePrefix() {
        UUID caseId = UUID.randomUUID();
        String prefix = "case-" + caseId + "/";
        Channel ch = channel(UUID.randomUUID(), prefix + "work");
        when(channelService.findByNamePrefix(prefix)).thenReturn(Uni.createFrom().item(List.of(ch)));

        List<CaseChannel> result = provider.listChannels(caseId).await().indefinitely();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).purpose()).isEqualTo("work");
        assertThat(result.get(0).backendType()).isEqualTo("qhorus");
    }

    @Test
    void listChannels_noChannels_returnsEmpty() {
        UUID caseId = UUID.randomUUID();
        when(channelService.findByNamePrefix(anyString()))
                .thenReturn(Uni.createFrom().item(List.of()));

        List<CaseChannel> result = provider.listChannels(UUID.randomUUID()).await().indefinitely();

        assertThat(result).isEmpty();
    }
}
