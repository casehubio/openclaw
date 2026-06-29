package io.casehub.openclaw.app.example;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.casehub.openclaw.app.OpenClawGroups;
import io.casehub.openclaw.casehub.OpenClawAgentConfigResolver;
import io.casehub.qhorus.api.message.CommitmentState;
import io.smallrye.common.annotation.Blocking;

/**
 * Demo REST endpoint for the casehub-openclaw example scenarios.
 *
 * <p>The handler is @Blocking: app uses quarkus-rest (RESTEasy Reactive) which runs JAX-RS
 * handlers on the event loop by default. Thread.sleep() in the polling loop would block the
 * event loop and prevent approve.sh (POST /openclaw/delivery/oversight/{gateId}) from being
 * processed — permanently deadlocking the demo. @Blocking moves the handler to a worker thread.
 *
 * <p>The endpoint exists in all deployments (RESTEasy registers @Path beans unconditionally).
 * When casehub.example.enabled=false (default), it returns 503. Set it to true in the
 * docker-compose environment for each example.
 */
@RolesAllowed(OpenClawGroups.ADMIN)
@ApplicationScoped
@Path("/example")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class ExampleController {

    private static final Logger log = Logger.getLogger(ExampleController.class);

    // Fixed caseId constants per example — enables ChannelContextWindow accumulation
    // across runs within the same JVM session (openChannel() is idempotent by channel name).
    private static final UUID CASE_ID_DEV_TEAM  = UUID.fromString("00000001-0000-0000-0000-000000000001");
    private static final UUID CASE_ID_TRADING   = UUID.fromString("00000002-0000-0000-0000-000000000002");
    private static final UUID CASE_ID_INCIDENT  = UUID.fromString("00000003-0000-0000-0000-000000000003");

    record AgentStep(String agentId, String commandContent) {}
    record ExampleDefinition(UUID caseId, List<AgentStep> steps) {}

    // commandContent is base task description only — buildPrompt() in OpenClawChannelBackend.post()
    // appends the full CaseHub commitment context block (commitmentId, casehub_done invocation)
    // automatically when correlationId is non-null. These are pre-canned; prior agent outputs
    // are not chained into subsequent commands (ExampleController sequences directly, not via output).
    private static final Map<String, ExampleDefinition> EXAMPLES = Map.of(
            "multi-agent-dev-team", new ExampleDefinition(CASE_ID_DEV_TEAM, List.of(
                    new AgentStep("planner",  "You are the Planner. Review GitHub issue #42."),
                    new AgentStep("coder",    "You are the Coder. Fix null check in PaymentService."),
                    new AgentStep("reviewer", "You are the Reviewer. Review diff for PaymentService.")
            )),
            "trading-oversight", new ExampleDefinition(CASE_ID_TRADING, List.of(
                    new AgentStep("signal",    "You are the Signal agent. Analyse NVDA market feed."),
                    new AgentStep("risk",      "You are the Risk agent. Assess: BUY 100 NVDA @ $892."),
                    new AgentStep("execution", "You are the Execution agent. Signal: BUY NVDA @ $892. Risk: MEDIUM. Place the order.")
            )),
            "incident-response", new ExampleDefinition(CASE_ID_INCIDENT, List.of(
                    new AgentStep("investigator", "You are the Investigator. P1 alert: payment-service error rate 34% since 02:47 UTC."),
                    new AgentStep("resolver",     "You are the Resolver. Root cause confirmed: deploy 7f3a2c1 reduced DB pool 20→5. Execute fix.")
            ))
    );

    private final ExampleSetup exampleSetup;
    private final ExamplePoller examplePoller;
    private final OpenClawAgentConfigResolver resolver;

    @ConfigProperty(name = "casehub.example.enabled", defaultValue = "false")
    boolean enabled;

    @ConfigProperty(name = "casehub.example.tenancyid", defaultValue = "demo")
    String tenancyId;

    @ConfigProperty(name = "casehub.example.timeout.seconds", defaultValue = "300")
    long timeoutSeconds;

    @Inject
    ExampleController(final ExampleSetup exampleSetup,
                      final ExamplePoller examplePoller,
                      final OpenClawAgentConfigResolver resolver) {
        this.exampleSetup = exampleSetup;
        this.examplePoller = examplePoller;
        this.resolver = resolver;
    }

    @POST
    @Path("/{exampleId}/start")
    @Blocking
    public Response start(@PathParam("exampleId") final String exampleId) {
        if (!enabled) {
            return Response.status(503)
                    .entity("{\"error\": \"Examples not enabled — set casehub.example.enabled=true\"}")
                    .build();
        }

        final ExampleDefinition def = EXAMPLES.get(exampleId);
        if (def == null) {
            final String validIds = EXAMPLES.keySet().stream()
                    .map(k -> "\"" + k + "\"")
                    .collect(Collectors.joining(", ", "[", "]"));
            return Response.status(400)
                    .entity("{\"error\": \"Unknown example: \\\"" + exampleId + "\\\". Valid: " + validIds + "\"}")
                    .build();
        }

        log.infof("Starting example '%s' caseId=%s", exampleId, def.caseId());
        final List<String> completedAgents = new ArrayList<>();

        for (final AgentStep step : def.steps()) {
            final String agentId = step.agentId();
            final OpenClawAgentConfigResolver.AgentConfig agentConfig = resolver.allAgents().get(agentId);
            if (agentConfig == null) {
                return Response.status(500)
                        .entity("{\"error\": \"Agent not configured: \\\"" + agentId
                                + "\\\". Add CASEHUB_OPENCLAW_AGENTS_"
                                + agentId.toUpperCase() + "_SESSION__KEY to docker-compose.\"}")
                        .build();
            }

            final String correlationId = UUID.randomUUID().toString();
            exampleSetup.setupAndDispatch(def.caseId(), tenancyId, agentId,
                    agentConfig.sessionKey(), correlationId, step.commandContent());

            log.infof("Dispatched COMMAND to %s (correlationId=%s) — waiting for completion",
                    agentId, correlationId);

            final CommitmentState state = pollUntilTerminal(correlationId);

            if (state == null) {
                log.warnf("Timeout or interrupt waiting for agent %s (correlationId=%s)",
                        agentId, correlationId);
                return Response.status(504)
                        .entity("{\"error\": \"Timeout waiting for agent: \\\"" + agentId + "\\\"\"}")
                        .build();
            }

            if (state == CommitmentState.DELEGATED) {
                log.infof("Agent %s escalated — commitment DELEGATED; operator takes over", agentId);
                return Response.ok(result("escalated",
                        "\"escalatedBy\": \"" + agentId + "\"", completedAgents)).build();
            }
            if (state == CommitmentState.DECLINED) {
                log.infof("Agent %s declined — stopping", agentId);
                return Response.ok(result("declined",
                        "\"declinedBy\": \"" + agentId + "\"", completedAgents)).build();
            }
            if (state != CommitmentState.FULFILLED) {
                log.warnf("Agent %s returned unexpected terminal state %s", agentId, state);
                return Response.status(500)
                        .entity("{\"error\": \"Unexpected terminal state: \\\"" + state + "\\\"\"}")
                        .build();
            }

            log.infof("Agent %s FULFILLED", agentId);
            completedAgents.add(agentId);
        }

        log.infof("Example '%s' complete. Steps: %s", exampleId, completedAgents);
        return Response.ok(result("complete", null, completedAgents)).build();
    }

    private CommitmentState pollUntilTerminal(final String correlationId) {
        final long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warnf("Polling interrupted for correlationId=%s", correlationId);
                return null;
            }
            final CommitmentState state = examplePoller.checkState(correlationId);
            if (state != null && state.isTerminal()) {
                return state;
            }
        }
        log.warnf("Polling timeout (%ds) for correlationId=%s", timeoutSeconds, correlationId);
        return null;
    }

    /** Builds a valid JSON response body with a properly serialised completedSteps list. */
    private static String result(final String resultType, final String extra,
                                  final List<String> completedAgents) {
        final String steps = completedAgents.stream()
                .map(s -> "\"" + s + "\"")
                .collect(Collectors.joining(", ", "[", "]"));
        final StringBuilder sb = new StringBuilder("{\"result\": \"").append(resultType).append("\"");
        if (extra != null) {
            sb.append(", ").append(extra);
        }
        sb.append(", \"completedSteps\": ").append(steps).append("}");
        return sb.toString();
    }
}
