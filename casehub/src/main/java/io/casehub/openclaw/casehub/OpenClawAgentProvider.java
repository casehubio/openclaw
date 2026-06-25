package io.casehub.openclaw.casehub;

import io.casehub.openclaw.client.OpenClawHookClient;
import io.casehub.openclaw.client.OpenClawInvocationException;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentProvider;
import io.casehub.platform.agent.AgentSession;
import io.casehub.platform.agent.AgentSessionConfig;
import io.casehub.platform.agent.AgentSessionInit;
import io.smallrye.mutiny.Multi;

import java.util.UUID;

public class OpenClawAgentProvider implements AgentProvider {

    private final DirectCallBridge bridge;
    private final OpenClawHookClient hookClient;
    private final String agentId;
    private final String deliveryBaseUrl;

    public OpenClawAgentProvider(DirectCallBridge bridge,
                                  OpenClawHookClient hookClient,
                                  String agentId,
                                  String deliveryBaseUrl) {
        this.bridge = bridge;
        this.hookClient = hookClient;
        this.agentId = agentId;
        this.deliveryBaseUrl = deliveryBaseUrl;
    }

    @Override
    public Multi<AgentEvent> invoke(AgentSessionConfig config) {
        return Multi.createFrom().emitter(emitter -> {
            String correlationId = config.correlationId() != null
                    ? config.correlationId() : UUID.randomUUID().toString();
            var future = bridge.submit(correlationId);
            String deliveryUrl = deliveryBaseUrl
                    + "/openclaw/direct-call/" + correlationId;

            String message = config.systemPrompt() + "\n\n" + config.userPrompt();

            emitter.onTermination(() -> bridge.cancel(correlationId));

            try {
                int timeout = config.timeout() != null
                        ? (int) config.timeout().toSeconds() : 120;
                hookClient.invokeDirect(agentId, message, null, timeout, deliveryUrl);
            } catch (OpenClawInvocationException e) {
                emitter.fail(e);
                return;
            }

            future.whenComplete((text, error) -> {
                if (error != null) {
                    emitter.fail(error);
                } else {
                    emitter.emit(new AgentEvent.TextDelta(text));
                    emitter.complete();
                }
            });
        });
    }

    @Override
    public AgentSession openSession(AgentSessionInit init) {
        throw new UnsupportedOperationException(
                "OpenClaw direct-call is single-shot — use invoke()");
    }
}
