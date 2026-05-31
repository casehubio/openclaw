package io.casehub.openclaw.app.mcp;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.message.MessageService;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolResponse;

/**
 * MCP tools for creating CaseHub work items and routing to queues.
 *
 * <p>Both tools are stateless: one Qhorus COMMAND dispatch per call.
 * The work channel naming convention is {@code work/<queueName>}.
 */
@ApplicationScoped
public class WorkitemTools {

    static final String WORK_CHANNEL_PREFIX = "work/";
    static final String OPENCLAW_SENDER = "casehub-openclaw";

    private final MessageService messageService;
    private final ChannelService channelService;

    @Inject
    public WorkitemTools(MessageService messageService, ChannelService channelService) {
        this.messageService = messageService;
        this.channelService = channelService;
    }

    @Tool(description = "Create a CaseHub work item with a deadline and Watchdog. "
            + "Provide either assignee OR queueName — not both. "
            + "Returns workitemId and confirmed deadline.")
    public ToolResponse createWorkitem(
            @ToolArg(description = "Your OpenClaw agentId") String agentId,
            @ToolArg(description = "Task description") String description,
            @ToolArg(description = "Deadline in ISO-8601 format (e.g. 2026-06-04T17:00:00Z)") String deadline,
            @ToolArg(description = "Target agent identifier; mutually exclusive with queueName",
                    required = false) String assignee,
            @ToolArg(description = "Target queue name (e.g. 'finance', 'home'); "
                    + "mutually exclusive with assignee", required = false) String queueName) {

        if (assignee != null && queueName != null) {
            return ToolResponse.error("ASSIGNEE_AND_QUEUE_CONFLICT: provide assignee OR queueName, not both");
        }

        Instant deadlineInstant;
        try {
            deadlineInstant = Instant.parse(deadline);
        } catch (DateTimeParseException e) {
            return ToolResponse.error("INVALID_DEADLINE: '" + deadline
                    + "' — use ISO-8601 format e.g. 2026-06-04T17:00:00Z");
        }

        // Route to the assignee's dedicated work channel when assignee is set;
        // fall back to the first generic work/ channel for unassigned tasks.
        // Qhorus routes COMMANDs by channel subscription, not by target field alone.
        Channel workChannel;
        if (assignee != null) {
            workChannel = channelService.findByName(WORK_CHANNEL_PREFIX + assignee)
                    .orElseGet(() -> channelService.findByNamePrefix(WORK_CHANNEL_PREFIX)
                            .stream().findFirst().orElse(null));
        } else {
            workChannel = channelService.findByNamePrefix(WORK_CHANNEL_PREFIX)
                    .stream().findFirst().orElse(null);
        }
        if (workChannel == null) {
            return ToolResponse.error("CASEHUB_UNAVAILABLE: no work/ channel found");
        }

        var builder = MessageDispatch.builder()
                .channelId(workChannel.id)
                .sender(OPENCLAW_SENDER)
                .type(MessageType.COMMAND)
                .content(description)
                .deadline(deadlineInstant)
                .actorType(ActorType.SYSTEM);

        if (assignee != null) {
            builder.target(assignee);
        }

        var result = messageService.dispatch(builder.build());

        return ToolResponse.success("""
                {"workitemId": "%s", "deadline": "%s", "watchdogArmed": true}
                """.formatted(result.messageId(), deadlineInstant).strip());
    }

    @Tool(description = "Route a task to a named CaseHub queue without specifying an assignee. "
            + "Whatever agent or person monitors the queue picks it up. "
            + "Returns routed confirmation with workitemId.")
    public ToolResponse queue(
            @ToolArg(description = "Your OpenClaw agentId") String agentId,
            @ToolArg(description = "Task description") String description,
            @ToolArg(description = "Queue name (e.g. 'finance', 'home', 'health')") String queueName,
            @ToolArg(description = "Priority: 'normal' or 'high'", required = false) String priority) {

        String channelName = WORK_CHANNEL_PREFIX + queueName;
        Optional<Channel> found = channelService.findByName(channelName);
        if (found.isEmpty()) {
            List<Channel> available = channelService.findByNamePrefix(WORK_CHANNEL_PREFIX);
            String names = available.stream()
                    .map(c -> c.name.replace(WORK_CHANNEL_PREFIX, ""))
                    .collect(Collectors.joining(", "));
            return ToolResponse.error("QUEUE_NOT_FOUND: '" + queueName
                    + "'. Available queues: " + (names.isEmpty() ? "(none)" : names));
        }

        Channel channel = found.get();
        String content = "high".equalsIgnoreCase(priority)
                ? "[HIGH PRIORITY] " + description
                : description;

        var result = messageService.dispatch(MessageDispatch.builder()
                .channelId(channel.id)
                .sender(OPENCLAW_SENDER)
                .type(MessageType.COMMAND)
                .content(content)
                .actorType(ActorType.SYSTEM)
                .build());

        return ToolResponse.success("""
                {"routed": true, "workitemId": "%s", "queueName": "%s"}
                """.formatted(result.messageId(), queueName).strip());
    }
}
