package io.casehub.openclaw.casehub.scenario;

import java.util.UUID;

/**
 * Result of ExampleSetup.setupAndDispatch() — captures the work and oversight
 * channel UUIDs so ScenarioExecutionService can register them with the state store.
 */
public record SetupResult(UUID workChannelId, UUID oversightChannelId) {}
