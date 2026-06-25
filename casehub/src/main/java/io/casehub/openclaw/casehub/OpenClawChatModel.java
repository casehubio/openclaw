package io.casehub.openclaw.casehub;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.casehub.api.model.ai.AgentException;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentProvider;
import io.casehub.platform.agent.AgentSessionConfig;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class OpenClawChatModel implements ChatModel {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentProvider agentProvider;
    private final Duration timeout;

    public OpenClawChatModel(AgentProvider agentProvider, Duration timeout) {
        this.agentProvider = agentProvider;
        this.timeout = timeout;
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        String systemPrompt = extractSystemPrompt(request.messages());
        String userText = extractLastUserText(request.messages());
        String userPromptWithSchema = prependSchema(request, userText);

        AgentSessionConfig config = new AgentSessionConfig(
                systemPrompt, userPromptWithSchema, List.of(), timeout, null);

        String responseText = agentProvider.invoke(config)
                .filter(AgentEvent.TextDelta.class::isInstance)
                .map(e -> ((AgentEvent.TextDelta) e).text())
                .collect().asList()
                .await().atMost(timeout)
                .stream()
                .collect(Collectors.joining());

        if (request.responseFormat() != null && request.responseFormat().jsonSchema() != null) {
            validateJson(responseText);
        }

        return ChatResponse.builder()
                .aiMessage(new AiMessage(responseText))
                .build();
    }

    private static String extractSystemPrompt(List<ChatMessage> messages) {
        return messages.stream()
                .filter(SystemMessage.class::isInstance)
                .map(m -> ((SystemMessage) m).text())
                .findFirst()
                .orElse("");
    }

    private static String extractLastUserText(List<ChatMessage> messages) {
        return messages.stream()
                .filter(UserMessage.class::isInstance)
                .map(m -> ((UserMessage) m).singleText())
                .reduce((first, second) -> second)
                .orElse("");
    }

    private static String prependSchema(ChatRequest request, String userText) {
        ResponseFormat format = request.responseFormat();
        if (format == null || format.jsonSchema() == null) {
            return userText;
        }
        JsonSchema schema = format.jsonSchema();
        String schemaBlock = serializeSchema(schema);
        return schemaBlock + "\n\n" + userText;
    }

    static String serializeSchema(JsonSchema schema) {
        StringBuilder sb = new StringBuilder();
        sb.append("Respond with JSON matching schema \"")
                .append(schema.name()).append("\":\n{\n");
        JsonSchemaElement root = schema.rootElement();
        if (root instanceof JsonObjectSchema obj) {
            Map<String, JsonSchemaElement> props = obj.properties();
            List<String> required = obj.required() != null ? obj.required() : List.of();
            new java.util.TreeMap<>(props).forEach((name, element) -> {
                String typeName = element.getClass().getSimpleName()
                        .replace("Json", "").replace("Schema", "").toLowerCase();
                String reqLabel = required.contains(name) ? " (required)" : "";
                sb.append("  \"").append(name).append("\": ")
                        .append(typeName).append(reqLabel).append(",\n");
            });
            if (!props.isEmpty()) {
                sb.setLength(sb.length() - 2);
                sb.append('\n');
            }
        }
        sb.append('}');
        return sb.toString();
    }

    private static void validateJson(String text) {
        if (text == null || text.isBlank()) {
            throw new AgentException("OpenClaw agent returned empty response");
        }
        try {
            MAPPER.readTree(text);
        } catch (Exception e) {
            throw new AgentException(
                    "OpenClaw agent returned invalid JSON: " + text, e);
        }
    }
}
