package io.casehub.openclaw.app.scenario;

import jakarta.annotation.security.PermitAll;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.casehub.openclaw.casehub.scenario.CaseExecutionEvent;
import io.casehub.openclaw.casehub.scenario.ScenarioEventListener;
import io.casehub.openclaw.casehub.scenario.ScenarioStateStore;
import io.smallrye.mutiny.Multi;

@PermitAll
@ApplicationScoped
@Path("/api/scenarios")
public class ScenarioSseResource {

    private final ScenarioStateStore stateStore;
    private final ObjectMapper objectMapper;

    @Inject
    public ScenarioSseResource(ScenarioStateStore stateStore, ObjectMapper objectMapper) {
        this.stateStore = stateStore;
        this.objectMapper = objectMapper;
    }

    @GET
    @Path("/events")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public Multi<OutboundSseEvent> events(@Context Sse sse) {
        return Multi.createFrom().<CaseExecutionEvent>emitter(emitter -> {
            ScenarioEventListener listener = emitter::emit;
            stateStore.addListener(listener);
            emitter.onTermination(() -> stateStore.removeListener(listener));
        }).map(event -> {
            try {
                return sse.newEventBuilder()
                        .name("message")
                        .data(objectMapper.writeValueAsString(event))
                        .build();
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        });
    }
}
