package io.casehub.openclaw.casehub;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import io.casehub.api.model.Capability;
import io.casehub.api.model.ProvisionContext;
import io.casehub.api.model.Worker;
import io.casehub.api.spi.ProvisioningException;
import io.casehub.api.spi.WorkerProvisioner;
import io.casehub.openclaw.context.ChannelContextWindowService;

/**
 * Provisions OpenClaw agents as CaseHub workers.
 *
 * <p>"Provisioning" means selecting the correct pre-configured OpenClaw agent for the
 * requested capabilities, registering it in the agent routing maps, and binding it to
 * the ChannelContextWindow. No process is started — OpenClaw agents are always-running.
 *
 * <p>MVP constraint: one OpenClaw agent per case. A second provision() for the same
 * caseId silently overwrites the previous entry in OpenClawAgentRegistry.
 */
@ApplicationScoped
public class OpenClawWorkerProvisioner implements WorkerProvisioner {

    private static final Logger log = Logger.getLogger(OpenClawWorkerProvisioner.class);

    private final ChannelContextWindowService service;
    private final OpenClawAgentRegistry registry;
    private final OpenClawCasehubConfig config;

    @Inject
    public OpenClawWorkerProvisioner(ChannelContextWindowService service,
                                      OpenClawAgentRegistry registry,
                                      OpenClawCasehubConfig config) {
        this.service = service;
        this.registry = registry;
        this.config = config;
    }

    @Override
    public Worker provision(Set<String> capabilities, ProvisionContext context) {
        String agentId = resolveAgentId(capabilities);
        String sessionKey = config.agents().get(agentId).sessionKey();
        UUID caseId = context.caseId();

        registry.register(agentId, caseId, sessionKey);
        service.bindAgent(agentId, caseId);

        log.infof("Provisioned OpenClaw agent: agentId=%s caseId=%s capabilities=%s",
                agentId, caseId, capabilities);

        List<Capability> capList = capabilities.stream()
                .map(c -> new Capability(c, null, null))
                .toList();
        return new Worker(agentId, capList, ctx -> Map.of());
    }

    @Override
    public void terminate(String workerId) {
        registry.deregister(workerId);
        log.infof("Terminated OpenClaw agent: agentId=%s", workerId);
    }

    @Override
    public Set<String> getCapabilities() {
        return config.agents().values().stream()
                .flatMap(e -> e.capabilities().stream())
                .collect(Collectors.toSet());
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
