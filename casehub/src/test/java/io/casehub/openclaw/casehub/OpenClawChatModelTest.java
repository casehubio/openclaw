package io.casehub.openclaw.casehub;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.casehub.api.model.ai.AgentException;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentProvider;
import io.casehub.platform.agent.AgentSession;
import io.casehub.platform.agent.AgentSessionConfig;
import io.casehub.platform.agent.AgentSessionInit;
import io.smallrye.mutiny.Multi;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenClawChatModelTest {

    private static AgentProvider stubProvider(Function<AgentSessionConfig, Multi<AgentEvent>> invoker) {
        return new AgentProvider() {
            @Override
            public Multi<AgentEvent> invoke(AgentSessionConfig config) {
                return invoker.apply(config);
            }

            @Override
            public AgentSession openSession(AgentSessionInit init) {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Test
    void doChat_extractsSystemPromptAndUserText() {
        AtomicReference<AgentSessionConfig> captured = new AtomicReference<>();
        AgentProvider provider = stubProvider(config -> {
            captured.set(config);
            return Multi.createFrom().item(new AgentEvent.TextDelta("{\"ok\":true}"));
        });

        OpenClawChatModel chatModel = new OpenClawChatModel(provider, Duration.ofSeconds(5));
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(
                        SystemMessage.from("You are a health agent"),
                        UserMessage.from("Book appointment")))
                .build();

        ChatResponse response = chatModel.doChat(request);

        assertThat(captured.get().systemPrompt()).isEqualTo("You are a health agent");
        assertThat(captured.get().userPrompt()).contains("Book appointment");
        assertThat(response.aiMessage().text()).isEqualTo("{\"ok\":true}");
    }

    @Test
    void doChat_extractsJsonSchemaIntoUserPrompt() {
        AtomicReference<AgentSessionConfig> captured = new AtomicReference<>();
        AgentProvider provider = stubProvider(config -> {
            captured.set(config);
            return Multi.createFrom().item(
                    new AgentEvent.TextDelta("{\"appointmentId\":\"A1\",\"confirmed\":true}"));
        });

        JsonObjectSchema schema = JsonObjectSchema.builder()
                .addStringProperty("appointmentId")
                .addBooleanProperty("confirmed")
                .required("appointmentId", "confirmed")
                .build();
        ResponseFormat format = ResponseFormat.builder()
                .type(ResponseFormatType.JSON)
                .jsonSchema(JsonSchema.builder()
                        .name("BookingResult")
                        .rootElement(schema)
                        .build())
                .build();

        OpenClawChatModel chatModel = new OpenClawChatModel(provider, Duration.ofSeconds(5));
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(
                        SystemMessage.from("sys"),
                        UserMessage.from("book it")))
                .responseFormat(format)
                .build();

        chatModel.doChat(request);

        String userPrompt = captured.get().userPrompt();
        assertThat(userPrompt).contains("BookingResult");
        assertThat(userPrompt).contains("appointmentId");
        assertThat(userPrompt).contains("book it");
    }

    @Test
    void doChat_plainTextResponse_noJsonValidation() {
        AgentProvider provider = stubProvider(config ->
                Multi.createFrom().item(new AgentEvent.TextDelta("not valid json")));

        OpenClawChatModel chatModel = new OpenClawChatModel(provider, Duration.ofSeconds(5));
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(SystemMessage.from("sys"), UserMessage.from("go")))
                .build();

        ChatResponse response = chatModel.doChat(request);
        assertThat(response.aiMessage().text()).isEqualTo("not valid json");
    }

    @Test
    void doChat_jsonSchemaRequested_invalidJson_throwsAgentException() {
        AgentProvider provider = stubProvider(config ->
                Multi.createFrom().item(new AgentEvent.TextDelta("not valid json")));

        JsonObjectSchema schema = JsonObjectSchema.builder()
                .addStringProperty("result")
                .build();
        ResponseFormat format = ResponseFormat.builder()
                .type(ResponseFormatType.JSON)
                .jsonSchema(JsonSchema.builder().name("Result").rootElement(schema).build())
                .build();

        OpenClawChatModel chatModel = new OpenClawChatModel(provider, Duration.ofSeconds(5));
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(SystemMessage.from("sys"), UserMessage.from("go")))
                .responseFormat(format)
                .build();

        assertThatThrownBy(() -> chatModel.doChat(request))
                .isInstanceOf(AgentException.class);
    }

    @Test
    void doChat_noSystemMessage_usesEmptyString() {
        AtomicReference<AgentSessionConfig> captured = new AtomicReference<>();
        AgentProvider provider = stubProvider(config -> {
            captured.set(config);
            return Multi.createFrom().item(new AgentEvent.TextDelta("{\"ok\":true}"));
        });

        OpenClawChatModel chatModel = new OpenClawChatModel(provider, Duration.ofSeconds(5));
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(UserMessage.from("just user text")))
                .build();

        chatModel.doChat(request);

        assertThat(captured.get().systemPrompt()).isEqualTo("");
    }

    @Test
    void doChat_emptyResponse_withJsonSchema_throwsAgentException() {
        AgentProvider provider = stubProvider(config ->
                Multi.createFrom().item(new AgentEvent.TextDelta("")));

        JsonObjectSchema schema = JsonObjectSchema.builder()
                .addStringProperty("result")
                .build();
        ResponseFormat format = ResponseFormat.builder()
                .type(ResponseFormatType.JSON)
                .jsonSchema(JsonSchema.builder().name("Result").rootElement(schema).build())
                .build();

        OpenClawChatModel chatModel = new OpenClawChatModel(provider, Duration.ofSeconds(5));
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(SystemMessage.from("sys"), UserMessage.from("go")))
                .responseFormat(format)
                .build();

        assertThatThrownBy(() -> chatModel.doChat(request))
                .isInstanceOf(AgentException.class);
    }
}
