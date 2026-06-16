package io.casehub.openclaw.app.example;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.message.CommitmentState;
import io.casehub.qhorus.runtime.message.Commitment;
import io.casehub.qhorus.runtime.store.CommitmentStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExamplePollerTest {

    CommitmentStore commitmentStore;
    ExamplePoller poller;

    @BeforeEach
    void setUp() {
        commitmentStore = mock(CommitmentStore.class);
        poller = new ExamplePoller(commitmentStore);
    }

    @Test
    void notFound_returnsNull() {
        when(commitmentStore.findByCorrelationId("unknown")).thenReturn(Optional.empty());
        assertThat(poller.checkState("unknown")).isNull();
    }

    @Test
    void fulfilled_returnsState() {
        final Commitment c = commitment(CommitmentState.FULFILLED);
        when(commitmentStore.findByCorrelationId("abc")).thenReturn(Optional.of(c));
        assertThat(poller.checkState("abc")).isEqualTo(CommitmentState.FULFILLED);
    }

    @Test
    void declined_isTerminal() {
        final Commitment c = commitment(CommitmentState.DECLINED);
        when(commitmentStore.findByCorrelationId("abc")).thenReturn(Optional.of(c));
        assertThat(poller.checkState("abc").isTerminal()).isTrue();
    }

    @Test
    void delegated_isTerminal() {
        // casehub_escalate transitions commitment to DELEGATED — ExampleController
        // must detect this and log escalation without dispatching the next agent.
        final Commitment c = commitment(CommitmentState.DELEGATED);
        when(commitmentStore.findByCorrelationId("abc")).thenReturn(Optional.of(c));
        final CommitmentState state = poller.checkState("abc");
        assertThat(state).isEqualTo(CommitmentState.DELEGATED);
        assertThat(state.isTerminal()).isTrue();
    }

    @Test
    void open_isNotTerminal() {
        final Commitment c = commitment(CommitmentState.OPEN);
        when(commitmentStore.findByCorrelationId("abc")).thenReturn(Optional.of(c));
        assertThat(poller.checkState("abc").isTerminal()).isFalse();
    }

    private static Commitment commitment(final CommitmentState state) {
        final Commitment c = new Commitment();
        c.state = state;
        return c;
    }
}
