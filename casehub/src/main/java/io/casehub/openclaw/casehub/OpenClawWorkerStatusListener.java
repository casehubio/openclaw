package io.casehub.openclaw.casehub;

import java.util.Map;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import io.casehub.api.model.WorkResult;
import io.casehub.api.spi.WorkerStatusListener;
import io.casehub.openclaw.context.ChannelContextWindowService;

/**
 * Reacts to CaseHub engine worker lifecycle events for OpenClaw agents.
 *
 * <p>Called by WorkflowExecutionCompletedHandler (engine runtime) via the
 * WORKER_EXECUTION_FINISHED Vert.x event bus address, which is published when
 * QhorusMessageSignalBridge delivers a DONE signal to CaseHubRuntime.signal().
 *
 * <p>onWorkerCompleted(): cleans up registry and ChannelContextWindow mappings.
 * onWorkerStalled(): fires CDI event; agent remains registered (Watchdog drives recovery).
 */
@ApplicationScoped
public class OpenClawWorkerStatusListener implements WorkerStatusListener {

    private static final Logger log = Logger.getLogger(OpenClawWorkerStatusListener.class);

    private final ChannelContextWindowService service;
    private final OpenClawAgentRegistry registry;
    private final Event<Object> events;

    @Inject
    public OpenClawWorkerStatusListener(ChannelContextWindowService service,
                                         OpenClawAgentRegistry registry,
                                         Event<Object> events) {
        this.service = service;
        this.registry = registry;
        this.events = events;
    }

    @Override
    public void onWorkerStarted(String workerId, Map<String, String> sessionMeta) {
        log.infof("OpenClaw agent started: agentId=%s", workerId);
    }

    @Override
    public void onWorkerCompleted(String workerId, WorkResult result) {
        log.infof("OpenClaw agent completed: agentId=%s status=%s", workerId, result.status());
        // Capture caseId before deregistering — registry removes the mappings on deregister()
        UUID caseId = registry.findCaseId(workerId).orElse(null);
        registry.deregister(workerId);
        service.unbindAgent(workerId);
        if (caseId != null) {
            service.closeCase(caseId);
        }
    }

    @Override
    public void onWorkerStalled(String workerId) {
        log.warnf("OpenClaw agent stalled: agentId=%s — remains registered; Watchdog drives recovery",
                workerId);
        events.fire(new WorkerStalledEvent(workerId));
    }

    /** Fired when an OpenClaw agent stalls. Observers may alert or trigger remediation. */
    public record WorkerStalledEvent(String agentId) {}
}
