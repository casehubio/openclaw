package io.casehub.openclaw.casehub;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import io.casehub.api.model.ProvisionContext;
import io.casehub.api.spi.ProvisionResult;
import io.casehub.api.spi.ProvisioningException;
import io.casehub.api.spi.ReactiveWorkerProvisioner;
import io.casehub.openclaw.context.ChannelContextWindowService;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.quarkus.arc.properties.IfBuildProperty;
import io.smallrye.mutiny.Uni;

/**
 * Reactive mirror of {@link OpenClawWorkerProvisioner} for Vert.x IO thread compatibility.
 *
 * <p>All operations are in-memory (ConcurrentHashMap) — no I/O. {@code Uni.createFrom().item(Supplier)}
 * is used throughout so {@link ProvisioningException} from {@code resolveAgentId()} propagates as a
 * Uni failure rather than being thrown synchronously on the subscribing thread.
 *
 * <p>Activated when {@code casehub.qhorus.reactive.enabled=true}, which also activates
 * {@code ReactiveChannelService} and {@code ReactiveMessageService}. Setting the openclaw
 * reactive beans to the same gate eliminates the risk of mismatched deployment state.
 */
@IfBuildProperty(name = "casehub.qhorus.reactive.enabled", stringValue = "true")
@ApplicationScoped
public class ReactiveOpenClawWorkerProvisioner implements ReactiveWorkerProvisioner {

    private static final Logger log = Logger.getLogger(ReactiveOpenClawWorkerProvisioner.class);

    private final ChannelContextWindowService service;
    private final OpenClawAgentRegistry registry;
    private final OpenClawCasehubConfig config;
    private final CurrentPrincipal currentPrincipal;

    @Inject
    public ReactiveOpenClawWorkerProvisioner(ChannelContextWindowService service,
                                              OpenClawAgentRegistry registry,
                                              OpenClawCasehubConfig config,
                                              CurrentPrincipal currentPrincipal) {
        this.service = service;
        this.registry = registry;
        this.config = config;
        this.currentPrincipal = currentPrincipal;
    }

    @Override
    public Uni<ProvisionResult> provision(Set<String> capabilities, ProvisionContext context) {
        // Capture tenancyId on the calling (request) thread — Uni.item(Supplier) executes
        // lazily on subscription, which may be a Vert.x worker thread where request scope
        // is not available.
        final String tenancyId = currentPrincipal.tenancyId();
        return Uni.createFrom().item(() -> {
            String agentId = resolveAgentId(capabilities);
            String sessionKey = config.agents().get(agentId).sessionKey();
            UUID caseId = context.caseId();
            registry.register(agentId, tenancyId, caseId, sessionKey);
            service.bindAgent(agentId, caseId);
            log.infof("Provisioned OpenClaw agent (reactive): agentId=%s caseId=%s tenancyId=%s capabilities=%s",
                    agentId, caseId, tenancyId, capabilities);
            return ProvisionResult.empty();
        });
    }

    @Override
    public Uni<Void> terminate(String workerId, String tenancyId) {
        return Uni.createFrom().<Void>item(() -> {
            registry.deregister(workerId);
            service.unbindAgent(workerId);
            log.infof("Terminated OpenClaw agent (reactive): agentId=%s tenancyId=%s", workerId, tenancyId);
            return null;
        });
    }

    @Override
    public Uni<Set<String>> getCapabilities() {
        return Uni.createFrom().item(() ->
                config.agents().values().stream()
                        .flatMap(e -> e.capabilities().stream())
                        .collect(Collectors.toSet()));
    }

    /**
     * Subset match: agent is a candidate if every requested capability is in its config set.
     * First match alphabetically wins. Logs a warning if multiple agents match.
     *
     * @throws ProvisioningException if no agent covers all requested capabilities
     */
    private String resolveAgentId(Set<String> requested) {
        List<String> candidates = config.agents().entrySet().stream()
                .filter(e -> e.getValue().capabilities().containsAll(requested))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();

        if (candidates.isEmpty()) {
            throw new ProvisioningException(
                    "No OpenClaw agent configured for capabilities: " + requested);
        }
        if (candidates.size() > 1) {
            log.warnf("Multiple agents match capabilities %s: %s — selecting %s. " +
                    "Check configuration for overlapping capability declarations.",
                    requested, candidates, candidates.get(0));
        }
        return candidates.get(0);
    }
}
