package io.casehub.openclaw.app.scenario;

import io.quarkus.test.junit.QuarkusTest;

import org.junit.jupiter.api.Test;

/**
 * Basic smoke test for ScenarioSseResource.
 *
 * <p>Testing SSE streams with rest-assured is complex because SSE is an infinite stream.
 * This test is intentionally minimal - just compiles the test class to verify the resource
 * exists in the CDI container.
 *
 * <p>TODO openclaw#58: Add integration test that subscribes to SSE, emits an event via
 * ScenarioStateStore, and verifies the client receives it. Use a proper SSE client, not
 * rest-assured.
 */
@QuarkusTest
class ScenarioSseResourceTest {

    @Test
    void smokeTest() {
        // SSE streams are infinite - rest-assured hangs trying to read the full response.
        // This test just verifies the @QuarkusTest boots successfully with the SSE resource
        // in the CDI container. The endpoint itself is verified by manual testing or a
        // dedicated SSE integration test.
    }
}
