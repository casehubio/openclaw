package io.casehub.openclaw.app.mcp;

import java.util.List;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.casehub.openclaw.context.ChannelContextWindowService;
import io.casehub.openclaw.context.WindowContent;
import io.casehub.qhorus.api.message.CommitmentState;
import io.casehub.qhorus.runtime.message.Commitment;
import io.casehub.qhorus.runtime.store.CommitmentStore;
import io.quarkiverse.mcp.server.Resource;
import io.quarkiverse.mcp.server.ResourceResponse;
import io.quarkiverse.mcp.server.ResourceTemplate;
import io.quarkiverse.mcp.server.ResourceTemplateArg;
import io.quarkiverse.mcp.server.TextResourceContents;

/**
 * MCP resources exposing real-time CaseHub state.
 *
 * <p>Resources are read-only and have no side effects.
 *
 * <ul>
 *   <li>{@code casehub://agent/{agentId}/commitments} — open commitments for an agent
 *   <li>{@code casehub://channel/{agentId}/recent} — recent channel context
 * </ul>
 */
@ApplicationScoped
public class CasehubMcpResources {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CommitmentStore commitmentStore;
    private final ChannelContextWindowService contextWindowService;

    @Inject
    public CasehubMcpResources(CommitmentStore commitmentStore,
                                ChannelContextWindowService contextWindowService) {
        this.commitmentStore = commitmentStore;
        this.contextWindowService = contextWindowService;
    }

    @ResourceTemplate(
            name = "agent-commitments",
            uriTemplate = "casehub://agent/{agentId}/commitments",
            description = "Open CaseHub commitments for this agent. "
                    + "Injected at session_start; also readable on demand. "
                    + "Returns OPEN and ESCALATED commitments only — terminal states excluded.")
    public ResourceResponse agentCommitments(
            @ResourceTemplateArg(name = "agentId") String agentId) {

        List<Commitment> open = commitmentStore.findAllOpen().stream()
                .filter(c -> agentId.equals(c.obligor))
                .filter(c -> c.state == CommitmentState.OPEN
                        || c.state == CommitmentState.ACKNOWLEDGED)
                .collect(Collectors.toList());

        StringBuilder sb = new StringBuilder();
        sb.append("{\"open\": [");
        for (int i = 0; i < open.size(); i++) {
            Commitment c = open.get(i);
            if (i > 0) sb.append(", ");
            sb.append("""
                    {"commitmentId": "%s", "state": "%s", "deadline": "%s", "watchdogArmed": true}
                    """.formatted(
                    c.correlationId,
                    c.state.name(),
                    c.expiresAt != null ? c.expiresAt : "none").strip());
        }
        sb.append("], \"count\": ").append(open.size()).append("}");

        return new ResourceResponse(List.of(
                new TextResourceContents("casehub://agent/" + agentId + "/commitments",
                        sb.toString(), "application/json", null)));
    }

    @ResourceTemplate(
            name = "channel-recent",
            uriTemplate = "casehub://channel/{agentId}/recent",
            description = "Recent Qhorus channel context for this agent from the "
                    + "ChannelContextWindow. Complements the before_prompt_build hook — "
                    + "provides on-demand retrieval for agents that want explicit control.")
    public ResourceResponse channelRecent(
            @ResourceTemplateArg(name = "agentId") String agentId) {

        // MCP resources return current full window (since=0) — LLMs don't track cursors.
        // Incremental fetching is the plugin's concern, handled via the before_prompt_build hook.
        WindowContent window = contextWindowService.query(agentId, 0L);

        String json;
        try {
            json = MAPPER.writeValueAsString(window);
        } catch (JsonProcessingException e) {
            json = "{\"error\": \"serialization failed\"}";
        }

        return new ResourceResponse(List.of(
                new TextResourceContents("casehub://channel/" + agentId + "/recent",
                        json, "application/json", null)));
    }
}
