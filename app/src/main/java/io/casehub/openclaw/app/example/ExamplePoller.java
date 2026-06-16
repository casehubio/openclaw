package io.casehub.openclaw.app.example;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import io.casehub.qhorus.api.message.CommitmentState;
import io.casehub.qhorus.runtime.store.CommitmentStore;

/**
 * Transactional JPA delegate for ExampleController's polling loop.
 *
 * <p>Each call to {@link #checkState} opens and closes its own transaction,
 * providing proper JPA context without holding a long-lived transaction across
 * the 2-second polling interval.
 *
 * <p>Returns {@code null} when the commitment is not yet visible (race between
 * COMMAND dispatch committing and the JPA read — keep polling). Returns a
 * terminal state when the agent has finished. Stop condition in the caller:
 * {@code state != null && state.isTerminal()}.
 */
@ApplicationScoped
class ExamplePoller {

    private final CommitmentStore commitmentStore;

    @Inject
    ExamplePoller(final CommitmentStore commitmentStore) {
        this.commitmentStore = commitmentStore;
    }

    @Transactional
    CommitmentState checkState(final String correlationId) {
        return commitmentStore.findByCorrelationId(correlationId)
                .map(c -> c.state)
                .orElse(null);
    }
}
