# Epic 6 — Bidirectional Wiring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire the full Qhorus ↔ OpenClaw bidirectional round trip with speech act classification and oversight gate groundwork, proven by an end-to-end `@QuarkusTest`.

**Architecture:** `ChannelBackend.post()` invokes OpenClaw via `POST /hooks/agent`; OpenClaw POSTs results to `/openclaw/delivery/channel/{channelId}`; `OversightGateService` classifies the result (Phase 1: always DONE, always AUTONOMOUS) and dispatches to the Qhorus work channel. The oversight gate path — fired when `ActionRiskClassifier` returns `GateRequired` — is fully wired and integration-tested even though Phase 1 never triggers it.

**Tech Stack:** Java 21, Quarkus 3.32.2, casehub-qhorus (MessageService, ChannelService, CommitmentStore), Mockito (unit tests), `@QuarkusTest` + `@InjectMock` (integration test), Maven multi-module.

**Design spec:** `docs/specs/2026-05-30-epic6-bidirectional-wiring-design.md`

**Build commands:**
```bash
# Full build
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn --batch-mode install

# Single test class (casehub module)
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn --batch-mode test -pl casehub -Dtest=<ClassName> -Dsurefire.failIfNoSpecifiedTests=false

# Single test class (core module)
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn --batch-mode test -pl core -Dtest=<ClassName> -Dsurefire.failIfNoSpecifiedTests=false

# Single test class (app module, builds deps first)
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn --batch-mode test -pl app -am -Dtest=<ClassName> -Dsurefire.failIfNoSpecifiedTests=false
```

---

## File Map

**Create:**
- `casehub/src/main/java/io/casehub/openclaw/casehub/CaseChannelNames.java` — package-private utility for channel name operations
- `casehub/src/main/java/io/casehub/openclaw/casehub/RiskDecision.java` — sealed interface: Autonomous | GateRequired
- `casehub/src/main/java/io/casehub/openclaw/casehub/PlannedAction.java` — record for ActionRiskClassifier input
- `casehub/src/main/java/io/casehub/openclaw/casehub/ActionRiskClassifier.java` — SPI interface (local placeholder for engine#402)
- `casehub/src/main/java/io/casehub/openclaw/casehub/DefaultActionRiskClassifier.java` — always AUTONOMOUS
- `casehub/src/main/java/io/casehub/openclaw/casehub/SpeechActContext.java` — record for SpeechActClassifier input
- `casehub/src/main/java/io/casehub/openclaw/casehub/SpeechActClassifier.java` — interface (groundwork for openclaw#10)
- `casehub/src/main/java/io/casehub/openclaw/casehub/DefaultSpeechActClassifier.java` — always DONE
- `casehub/src/main/java/io/casehub/openclaw/casehub/OversightGateService.java` — evaluate() + fulfill()
- `app/src/main/java/io/casehub/openclaw/app/OpenClawOversightDeliveryPayload.java` — record for oversight webhook
- `app/src/main/java/io/casehub/openclaw/app/OpenClawOversightDeliveryResource.java` — `POST /openclaw/delivery/oversight/{gateId}`
- `casehub/src/test/java/io/casehub/openclaw/casehub/CaseChannelNamesTest.java`
- `casehub/src/test/java/io/casehub/openclaw/casehub/DefaultActionRiskClassifierTest.java`
- `casehub/src/test/java/io/casehub/openclaw/casehub/DefaultSpeechActClassifierTest.java`
- `casehub/src/test/java/io/casehub/openclaw/casehub/OversightGateServiceTest.java`
- `app/src/test/java/io/casehub/openclaw/app/OpenClawOversightDeliveryResourceTest.java`
- `app/src/test/java/io/casehub/openclaw/app/BidirectionalWiringIT.java`

**Modify:**
- `casehub/src/main/java/io/casehub/openclaw/casehub/OpenClawChannelBackend.java` — delegate `extractCaseId()` to `CaseChannelNames`
- `casehub/src/main/java/io/casehub/openclaw/casehub/OpenClawCaseChannelProvider.java` — oversight `allowedTypes` → null
- `casehub/src/main/java/io/casehub/openclaw/casehub/OpenClawCasehubConfig.java` — add `Oversight` config group
- `core/src/main/java/io/casehub/openclaw/client/OpenClawHookClient.java` — add `invoke()` overload with explicit `deliveryUrl`
- `app/src/main/java/io/casehub/openclaw/app/OpenClawDeliveryResource.java` — delegate to `OversightGateService.evaluate()`
- `core/src/test/java/io/casehub/openclaw/client/OpenClawHookClientTest.java` — test new overload
- `app/src/test/java/io/casehub/openclaw/app/OpenClawDeliveryResourceTest.java` — update for delegation

---

## Task 1: `CaseChannelNames` utility + update `OpenClawChannelBackend`

**Files:**
- Create: `casehub/src/main/java/io/casehub/openclaw/casehub/CaseChannelNames.java`
- Create: `casehub/src/test/java/io/casehub/openclaw/casehub/CaseChannelNamesTest.java`
- Modify: `casehub/src/main/java/io/casehub/openclaw/casehub/OpenClawChannelBackend.java`

- [ ] **Write the failing tests**

```java
// casehub/src/test/java/io/casehub/openclaw/casehub/CaseChannelNamesTest.java
package io.casehub.openclaw.casehub;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CaseChannelNamesTest {

    @Test
    void extractCaseId_withSuffix_returnsCaseId() {
        UUID id = UUID.randomUUID();
        assertThat(CaseChannelNames.extractCaseId("case-" + id + "/work")).isEqualTo(id);
    }

    @Test
    void extractCaseId_withOversightSuffix_returnsCaseId() {
        UUID id = UUID.randomUUID();
        assertThat(CaseChannelNames.extractCaseId("case-" + id + "/oversight")).isEqualTo(id);
    }

    @Test
    void extractCaseId_noCasePrefix_returnsNull() {
        assertThat(CaseChannelNames.extractCaseId("other-channel")).isNull();
    }

    @Test
    void extractCaseId_invalidUuid_returnsNull() {
        assertThat(CaseChannelNames.extractCaseId("case-not-a-uuid/work")).isNull();
    }

    @Test
    void workChannelName_roundTrip() {
        UUID id = UUID.randomUUID();
        assertThat(CaseChannelNames.extractCaseId(CaseChannelNames.workChannelName(id))).isEqualTo(id);
    }

    @Test
    void oversightChannelName_roundTrip() {
        UUID id = UUID.randomUUID();
        assertThat(CaseChannelNames.extractCaseId(CaseChannelNames.oversightChannelName(id))).isEqualTo(id);
    }

    @Test
    void workChannelName_format() {
        UUID id = UUID.randomUUID();
        assertThat(CaseChannelNames.workChannelName(id)).isEqualTo("case-" + id + "/work");
    }

    @Test
    void oversightChannelName_format() {
        UUID id = UUID.randomUUID();
        assertThat(CaseChannelNames.oversightChannelName(id)).isEqualTo("case-" + id + "/oversight");
    }
}
```

- [ ] **Run tests to verify they fail**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn --batch-mode test -pl casehub -Dtest=CaseChannelNamesTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: FAIL — `CaseChannelNames` does not exist.

- [ ] **Implement `CaseChannelNames`**

```java
// casehub/src/main/java/io/casehub/openclaw/casehub/CaseChannelNames.java
package io.casehub.openclaw.casehub;

import java.util.UUID;

import io.casehub.api.model.CaseChannel;

/** Package-private utility for case channel name operations. */
class CaseChannelNames {

    private CaseChannelNames() {}

    static UUID extractCaseId(String channelName) {
        if (!channelName.startsWith(CaseChannel.CASE_CHANNEL_PREFIX)) return null;
        String withoutPrefix = channelName.substring(CaseChannel.CASE_CHANNEL_PREFIX.length());
        int slash = withoutPrefix.indexOf('/');
        String uuidStr = slash >= 0 ? withoutPrefix.substring(0, slash) : withoutPrefix;
        try {
            return UUID.fromString(uuidStr);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static String workChannelName(UUID caseId) {
        return CaseChannel.CASE_CHANNEL_PREFIX + caseId + "/work";
    }

    static String oversightChannelName(UUID caseId) {
        return CaseChannel.CASE_CHANNEL_PREFIX + caseId + "/oversight";
    }
}
```

- [ ] **Update `OpenClawChannelBackend.extractCaseId()` to delegate**

Replace the body of `extractCaseId()` in `OpenClawChannelBackend.java`:

```java
// Before (lines ~121-130):
UUID extractCaseId(String channelName) {
    if (!channelName.startsWith(CaseChannel.CASE_CHANNEL_PREFIX)) return null;
    String withoutPrefix = channelName.substring(CaseChannel.CASE_CHANNEL_PREFIX.length());
    int slash = withoutPrefix.indexOf('/');
    String uuidStr = slash >= 0 ? withoutPrefix.substring(0, slash) : withoutPrefix;
    try {
        return UUID.fromString(uuidStr);
    } catch (IllegalArgumentException e) {
        return null;
    }
}

// After:
UUID extractCaseId(String channelName) {
    return CaseChannelNames.extractCaseId(channelName);
}
```

- [ ] **Run all casehub tests to verify nothing broke**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn --batch-mode test -pl casehub -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: All green including existing `OpenClawChannelBackendTest`.

- [ ] **Commit**

```bash
git -C /Users/mdproctor/claude/casehub/openclaw add casehub/src/
git -C /Users/mdproctor/claude/casehub/openclaw commit -m "feat(casehub): CaseChannelNames utility; delegate ChannelBackend.extractCaseId()

Refs #6"
```

---

## Task 2: `ActionRiskClassifier` — types, interface, default implementation

**Files:**
- Create: `casehub/src/main/java/io/casehub/openclaw/casehub/RiskDecision.java`
- Create: `casehub/src/main/java/io/casehub/openclaw/casehub/PlannedAction.java`
- Create: `casehub/src/main/java/io/casehub/openclaw/casehub/ActionRiskClassifier.java`
- Create: `casehub/src/main/java/io/casehub/openclaw/casehub/DefaultActionRiskClassifier.java`
- Create: `casehub/src/test/java/io/casehub/openclaw/casehub/DefaultActionRiskClassifierTest.java`

- [ ] **Write the failing test**

```java
// casehub/src/test/java/io/casehub/openclaw/casehub/DefaultActionRiskClassifierTest.java
package io.casehub.openclaw.casehub;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DefaultActionRiskClassifierTest {

    DefaultActionRiskClassifier classifier = new DefaultActionRiskClassifier();

    @Test
    void classify_anyAction_returnsAutonomous() {
        PlannedAction action = new PlannedAction("finance-agent", UUID.randomUUID(),
                "cancel subscription", "subscription.cancel", Map.of());
        assertThat(classifier.classify(action)).isInstanceOf(RiskDecision.Autonomous.class);
    }

    @Test
    void classify_nullFields_returnsAutonomous() {
        PlannedAction action = new PlannedAction("agent", UUID.randomUUID(), "desc", null, Map.of());
        assertThat(classifier.classify(action)).isInstanceOf(RiskDecision.Autonomous.class);
    }
}
```

- [ ] **Run to verify it fails**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn --batch-mode test -pl casehub -Dtest=DefaultActionRiskClassifierTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: FAIL — types do not exist.

- [ ] **Implement `RiskDecision`**

```java
// casehub/src/main/java/io/casehub/openclaw/casehub/RiskDecision.java
package io.casehub.openclaw.casehub;

public sealed interface RiskDecision permits RiskDecision.Autonomous, RiskDecision.GateRequired {
    record Autonomous() implements RiskDecision {}
    record GateRequired(String reason, boolean reversible) implements RiskDecision {}
}
```

- [ ] **Implement `PlannedAction`**

```java
// casehub/src/main/java/io/casehub/openclaw/casehub/PlannedAction.java
package io.casehub.openclaw.casehub;

import java.util.Map;
import java.util.UUID;

/**
 * Describes a consequential action a worker proposes to take.
 *
 * <p>Fields are intentionally compatible with the {@code ActionRiskClassifier} SPI
 * proposed for casehub-engine-api (casehubio/engine#402). Migration is a pure import
 * swap when that SPI ships.
 *
 * @param workerId   the OpenClaw agentId performing the action
 * @param caseId     the case this action belongs to
 * @param description the agent's output — what it proposes to do (human-readable)
 * @param actionType  structured tag (e.g. "subscription.cancel"); null in Phase 1
 * @param context     domain-specific facts (e.g. amount, target); empty map in Phase 1
 */
public record PlannedAction(
        String workerId,
        UUID caseId,
        String description,
        String actionType,
        Map<String, String> context
) {}
```

- [ ] **Implement `ActionRiskClassifier`**

```java
// casehub/src/main/java/io/casehub/openclaw/casehub/ActionRiskClassifier.java
package io.casehub.openclaw.casehub;

/**
 * Classifies the risk of a proposed worker action, deciding whether autonomous
 * execution or a human oversight gate is required.
 *
 * <p><b>Phase 1 (DefaultActionRiskClassifier):</b> always {@link RiskDecision.Autonomous}.
 * No risk rules are configured — all actions proceed without oversight.
 *
 * <p>This is a local placeholder for the {@code ActionRiskClassifier} SPI proposed for
 * {@code casehub-engine-api} (casehubio/engine#402). When that SPI ships, replace this
 * interface and its implementations with the engine-api import — the contract is
 * identical by design.
 *
 * <p>Override the default bean with {@code @Alternative @Priority(1)}.
 */
public interface ActionRiskClassifier {
    RiskDecision classify(PlannedAction action);
}
```

- [ ] **Implement `DefaultActionRiskClassifier`**

```java
// casehub/src/main/java/io/casehub/openclaw/casehub/DefaultActionRiskClassifier.java
package io.casehub.openclaw.casehub;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class DefaultActionRiskClassifier implements ActionRiskClassifier {

    @Override
    public RiskDecision classify(PlannedAction action) {
        return new RiskDecision.Autonomous();
    }
}
```

- [ ] **Run tests to verify they pass**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn --batch-mode test -pl casehub -Dtest=DefaultActionRiskClassifierTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS.

- [ ] **Commit**

```bash
git -C /Users/mdproctor/claude/casehub/openclaw add casehub/src/
git -C /Users/mdproctor/claude/casehub/openclaw commit -m "feat(casehub): ActionRiskClassifier SPI + Phase 1 default (always AUTONOMOUS)

Refs #6"
```

---

## Task 3: `SpeechActClassifier` — interface and default implementation

**Files:**
- Create: `casehub/src/main/java/io/casehub/openclaw/casehub/SpeechActContext.java`
- Create: `casehub/src/main/java/io/casehub/openclaw/casehub/SpeechActClassifier.java`
- Create: `casehub/src/main/java/io/casehub/openclaw/casehub/DefaultSpeechActClassifier.java`
- Create: `casehub/src/test/java/io/casehub/openclaw/casehub/DefaultSpeechActClassifierTest.java`

- [ ] **Write the failing test**

```java
// casehub/src/test/java/io/casehub/openclaw/casehub/DefaultSpeechActClassifierTest.java
package io.casehub.openclaw.casehub;

import org.junit.jupiter.api.Test;
import io.casehub.qhorus.api.message.MessageType;
import static org.assertj.core.api.Assertions.assertThat;

class DefaultSpeechActClassifierTest {

    DefaultSpeechActClassifier classifier = new DefaultSpeechActClassifier();

    @Test
    void classify_normalOutput_returnsDone() {
        assertThat(classifier.classify(new SpeechActContext("agent", "Analysis complete.", "finance")))
                .isEqualTo(MessageType.DONE);
    }

    @Test
    void classify_emptyOutput_returnsDone() {
        assertThat(classifier.classify(new SpeechActContext("agent", "", null)))
                .isEqualTo(MessageType.DONE);
    }

    @Test
    void classify_nullActionType_returnsDone() {
        assertThat(classifier.classify(new SpeechActContext("agent", "result", null)))
                .isEqualTo(MessageType.DONE);
    }
}
```

- [ ] **Run to verify it fails**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn --batch-mode test -pl casehub -Dtest=DefaultSpeechActClassifierTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: FAIL.

- [ ] **Implement `SpeechActContext`**

```java
// casehub/src/main/java/io/casehub/openclaw/casehub/SpeechActContext.java
package io.casehub.openclaw.casehub;

/**
 * Input to {@link SpeechActClassifier} describing an agent's output.
 *
 * @param agentId    the OpenClaw agent that produced the output
 * @param output     the raw agent output text
 * @param actionType structured tag for the action type; null in Phase 1
 */
public record SpeechActContext(String agentId, String output, String actionType) {}
```

- [ ] **Implement `SpeechActClassifier`**

```java
// casehub/src/main/java/io/casehub/openclaw/casehub/SpeechActClassifier.java
package io.casehub.openclaw.casehub;

import io.casehub.qhorus.api.message.MessageType;

/**
 * Classifies an OpenClaw agent output into a Qhorus {@link MessageType}.
 *
 * <p><b>Phase 1 ({@link DefaultSpeechActClassifier}):</b> always returns
 * {@link MessageType#DONE}. Inferred from invocation context — a COMMAND was
 * received and this is the agent's completion response.
 *
 * <p><b>Phase 2 (openclaw#10):</b> detect skill-output prefix conventions prepended
 * by SKILL.md instructions — e.g. "[STATUS] Boiler pressure 1.2 bar" → STATUS.
 *
 * <p><b>Phase 3 (openclaw#10):</b> parse structured JSON output from skills that
 * provide machine-readable speech acts: {@code {"type":"STATUS","content":"..."}}.
 *
 * <p>This interface exists now to isolate classification from {@link OversightGateService}.
 * Phase 2/3 implementations are drop-in replacements. Override with
 * {@code @Alternative @Priority(1)}.
 */
public interface SpeechActClassifier {
    MessageType classify(SpeechActContext context);
}
```

- [ ] **Implement `DefaultSpeechActClassifier`**

```java
// casehub/src/main/java/io/casehub/openclaw/casehub/DefaultSpeechActClassifier.java
package io.casehub.openclaw.casehub;

import jakarta.enterprise.context.ApplicationScoped;
import io.casehub.qhorus.api.message.MessageType;

@ApplicationScoped
public class DefaultSpeechActClassifier implements SpeechActClassifier {

    @Override
    public MessageType classify(SpeechActContext context) {
        return MessageType.DONE;
    }
}
```

- [ ] **Run tests to verify they pass**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn --batch-mode test -pl casehub -Dtest=DefaultSpeechActClassifierTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS.

- [ ] **Commit**

```bash
git -C /Users/mdproctor/claude/casehub/openclaw add casehub/src/
git -C /Users/mdproctor/claude/casehub/openclaw commit -m "feat(casehub): SpeechActClassifier interface + Phase 1 default (always DONE)

Refs #6"
```

---

## Task 4: `OpenClawCasehubConfig` — add oversight config group

**Files:**
- Modify: `casehub/src/main/java/io/casehub/openclaw/casehub/OpenClawCasehubConfig.java`

No test needed — `@ConfigMapping` interfaces are validated at application startup; integration test in Task 9 will catch wiring errors.

- [ ] **Add `Oversight` interface to `OpenClawCasehubConfig`**

```java
// casehub/src/main/java/io/casehub/openclaw/casehub/OpenClawCasehubConfig.java
package io.casehub.openclaw.casehub;

import java.util.List;
import java.util.Map;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "casehub.openclaw")
public interface OpenClawCasehubConfig {

    Map<String, AgentEntry> agents();

    /** Oversight gate configuration. All properties are optional (Phase 1: gate never fires). */
    Oversight oversight();

    interface AgentEntry {
        List<String> capabilities();
        String sessionKey();
    }

    interface Oversight {
        /**
         * Agent used to deliver oversight gate questions to humans via messaging.
         * Defaults to the work agent if blank — acceptable for Phase 1 since gate never fires.
         * Phase 2: configure a dedicated messaging agent (e.g. "home-messaging-agent").
         *
         * Property: casehub.openclaw.oversight.agent-id
         */
        @WithDefault("")
        String agentId();
    }
}
```

- [ ] **Build casehub module to verify config mapping compiles**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn --batch-mode install -pl casehub -DskipTests
```
Expected: BUILD SUCCESS.

- [ ] **Commit**

```bash
git -C /Users/mdproctor/claude/casehub/openclaw add casehub/src/main/
git -C /Users/mdproctor/claude/casehub/openclaw commit -m "feat(casehub): OpenClawCasehubConfig oversight config group

Refs #6"
```

---

## Task 5: `OpenClawHookClient` — add `invoke()` overload with explicit delivery URL

**Files:**
- Modify: `core/src/main/java/io/casehub/openclaw/client/OpenClawHookClient.java`
- Modify: `core/src/test/java/io/casehub/openclaw/client/OpenClawHookClientTest.java`

- [ ] **Write the failing test** — add to existing `OpenClawHookClientTest`

Find the existing `OpenClawHookClientTest` class. Add this test:

```java
// Add to existing class in core/src/test/java/io/casehub/openclaw/client/OpenClawHookClientTest.java

@Test
void invoke_withExplicitDeliveryUrl_usesProvidedUrlNotSessionUrl() {
    // arrange
    OpenClawGatewayClient gatewayClient = mock(OpenClawGatewayClient.class);
    OpenClawClientConfig config = configWith("http://host", "claude-opus-4-5", 120);
    OpenClawHookClient client = new OpenClawHookClient(gatewayClient, config);

    Response okResponse = mock(Response.class);
    when(okResponse.getStatus()).thenReturn(200);
    when(gatewayClient.invokeAgent(any(AgentInvocationRequest.class))).thenReturn(okResponse);

    client.registerSession("my-agent", "sk-abc", "http://host/channel/123");

    // act — invoke with a DIFFERENT delivery URL (the oversight endpoint)
    client.invoke("my-agent", "approve this?", "claude-opus-4-5", 30,
            "http://host/openclaw/delivery/oversight/gate-uuid");

    // assert — gatewayClient received the explicit URL, not the session's registered URL
    ArgumentCaptor<AgentInvocationRequest> captor = ArgumentCaptor.forClass(AgentInvocationRequest.class);
    verify(gatewayClient).invokeAgent(captor.capture());
    assertThat(captor.getValue().to()).isEqualTo("http://host/openclaw/delivery/oversight/gate-uuid");
    assertThat(captor.getValue().message()).isEqualTo("approve this?");
    assertThat(captor.getValue().agentId()).isEqualTo("my-agent");
    assertThat(captor.getValue().deliver()).isEqualTo("webhook");
}
```

Note: `ArgumentCaptor` is imported from `org.mockito.ArgumentCaptor`. Add import to the test file.

- [ ] **Run to verify it fails**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn --batch-mode test -pl core -Dtest=OpenClawHookClientTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: FAIL — overload does not exist.

- [ ] **Add the `invoke()` overload to `OpenClawHookClient`**

First, refactor the existing `invoke(String, String, String, int)` to delegate to the new overload:

```java
// In OpenClawHookClient.java — replace the existing invoke() and add overload

/**
 * Invokes an OpenClaw agent using the delivery URL from the registered session.
 */
public void invoke(String agentId, String message, String model, int timeoutSeconds) {
    OpenClawSession session = sessions.get(agentId);
    if (session == null) {
        throw new OpenClawInvocationException("No session registered for agentId: " + agentId);
    }
    invoke(agentId, message, model, timeoutSeconds, session.webhookUrl());
}

/**
 * Invokes an OpenClaw agent using an explicit delivery URL.
 * The registered session's sessionKey is still used for authentication.
 * Use this overload when the delivery target differs from the session's default webhook URL
 * (e.g. the oversight delivery endpoint for a gate invocation).
 *
 * @param deliveryUrl the webhook URL OpenClaw will POST the result to
 */
public void invoke(String agentId, String message, String model, int timeoutSeconds, String deliveryUrl) {
    OpenClawSession session = sessions.get(agentId);
    if (session == null) {
        throw new OpenClawInvocationException("No session registered for agentId: " + agentId);
    }

    String effectiveModel = (model == null || model.isBlank())
            ? config.agent().defaultModel()
            : model;

    int effectiveTimeout = (timeoutSeconds > 0)
            ? timeoutSeconds
            : config.agent().defaultTimeoutSeconds();

    AgentInvocationRequest request = AgentInvocationRequest.forWebhook(
            message, agentId, deliveryUrl,
            effectiveModel, effectiveTimeout, session.sessionKey(), null);

    try {
        Response response = gatewayClient.invokeAgent(request);
        try {
            if (response.getStatus() / 100 != 2) {
                throw new OpenClawInvocationException(
                        "OpenClaw /hooks/agent returned HTTP " + response.getStatus()
                        + " for agentId: " + agentId);
            }
        } finally {
            response.close();
        }
    } catch (OpenClawInvocationException e) {
        throw e;
    } catch (WebApplicationException e) {
        int status = e.getResponse() != null ? e.getResponse().getStatus() : -1;
        throw new OpenClawInvocationException(
                "OpenClaw /hooks/agent returned HTTP " + status
                + " for agentId: " + agentId);
    }
}
```

- [ ] **Run all core tests**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn --batch-mode test -pl core -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: All green.

- [ ] **Commit**

```bash
git -C /Users/mdproctor/claude/casehub/openclaw add core/src/
git -C /Users/mdproctor/claude/casehub/openclaw commit -m "feat(core): OpenClawHookClient.invoke() overload with explicit deliveryUrl

Refs #6"
```

---

## Task 6: `OversightGateService`

**Files:**
- Create: `casehub/src/main/java/io/casehub/openclaw/casehub/OversightGateService.java`
- Create: `casehub/src/test/java/io/casehub/openclaw/casehub/OversightGateServiceTest.java`

- [ ] **Write the failing unit tests**

```java
// casehub/src/test/java/io/casehub/openclaw/casehub/OversightGateServiceTest.java
package io.casehub.openclaw.casehub;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.casehub.openclaw.client.OpenClawClientConfig;
import io.casehub.openclaw.client.OpenClawHookClient;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.message.Commitment;
import io.casehub.qhorus.runtime.message.MessageService;
import io.casehub.qhorus.runtime.store.CommitmentStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OversightGateServiceTest {

    ChannelService channelService;
    MessageService messageService;
    CommitmentStore commitmentStore;
    OpenClawHookClient hookClient;
    OpenClawClientConfig clientConfig;
    OpenClawCasehubConfig casehubConfig;
    SpeechActClassifier speechActClassifier;
    ActionRiskClassifier actionRiskClassifier;
    OversightGateService service;

    UUID caseId = UUID.randomUUID();
    UUID workChannelId = UUID.randomUUID();
    UUID oversightChannelId = UUID.randomUUID();
    Channel workChannel;
    Channel oversightChannel;

    @BeforeEach
    void setup() {
        channelService = mock(ChannelService.class);
        messageService = mock(MessageService.class);
        commitmentStore = mock(CommitmentStore.class);
        hookClient = mock(OpenClawHookClient.class);
        clientConfig = mock(OpenClawClientConfig.class);
        casehubConfig = mock(OpenClawCasehubConfig.class);
        speechActClassifier = mock(SpeechActClassifier.class);
        actionRiskClassifier = mock(ActionRiskClassifier.class);

        workChannel = new Channel();
        workChannel.id = workChannelId;
        workChannel.name = "case-" + caseId + "/work";

        oversightChannel = new Channel();
        oversightChannel.id = oversightChannelId;
        oversightChannel.name = "case-" + caseId + "/oversight";

        when(channelService.findById(workChannelId)).thenReturn(Optional.of(workChannel));
        when(channelService.findByName("case-" + caseId + "/oversight"))
                .thenReturn(Optional.of(oversightChannel));
        when(channelService.findByName("case-" + caseId + "/work"))
                .thenReturn(Optional.of(workChannel));

        // Default: DONE + AUTONOMOUS
        when(speechActClassifier.classify(any())).thenReturn(MessageType.DONE);
        when(actionRiskClassifier.classify(any())).thenReturn(new RiskDecision.Autonomous());

        // Config stubs
        OpenClawClientConfig.Delivery delivery = mock(OpenClawClientConfig.Delivery.class);
        when(delivery.baseUrl()).thenReturn("http://casehub");
        when(clientConfig.delivery()).thenReturn(delivery);

        OpenClawClientConfig.Agent agent = mock(OpenClawClientConfig.Agent.class);
        when(agent.defaultModel()).thenReturn("claude-opus-4-5");
        when(agent.defaultTimeoutSeconds()).thenReturn(120);
        when(clientConfig.agent()).thenReturn(agent);

        OpenClawCasehubConfig.Oversight oversight = mock(OpenClawCasehubConfig.Oversight.class);
        when(oversight.agentId()).thenReturn("");
        when(casehubConfig.oversight()).thenReturn(oversight);

        service = new OversightGateService(channelService, messageService, commitmentStore,
                hookClient, clientConfig, casehubConfig, speechActClassifier, actionRiskClassifier);
    }

    // ── evaluate() — autonomous path ──────────────────────────────────────────

    @Test
    void evaluate_autonomous_dispatchesDoneToWorkChannel() {
        service.evaluate(workChannelId, "finance-agent", "Analysis complete.");

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());
        MessageDispatch dispatched = captor.getValue();
        assertThat(dispatched.channelId()).isEqualTo(workChannelId);
        assertThat(dispatched.type()).isEqualTo(MessageType.DONE);
        assertThat(dispatched.sender()).isEqualTo("finance-agent");
        assertThat(dispatched.content()).isEqualTo("Analysis complete.");
        assertThat(dispatched.actorType()).isEqualTo(ActorType.AGENT);
    }

    @Test
    void evaluate_autonomous_doesNotInvokeOpenClaw() {
        service.evaluate(workChannelId, "finance-agent", "done");
        verify(hookClient, never()).invoke(anyString(), anyString(), anyString(), anyInt(), anyString());
    }

    // ── evaluate() — gate required path ───────────────────────────────────────

    @Test
    void evaluate_gateRequired_postsCommandToOversightWithCorrelationId() {
        when(actionRiskClassifier.classify(any()))
                .thenReturn(new RiskDecision.GateRequired("spending limit exceeded", false));

        service.evaluate(workChannelId, "finance-agent", "Cancel Netflix subscription.");

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());
        MessageDispatch dispatched = captor.getValue();
        assertThat(dispatched.channelId()).isEqualTo(oversightChannelId);
        assertThat(dispatched.type()).isEqualTo(MessageType.COMMAND);
        assertThat(dispatched.sender()).isEqualTo("finance-agent");
        assertThat(dispatched.correlationId()).isNotNull();
        assertThat(dispatched.content()).contains("Cancel Netflix subscription.");
    }

    @Test
    void evaluate_gateRequired_invokesOpenClawWithOversightDeliveryUrl() {
        when(actionRiskClassifier.classify(any()))
                .thenReturn(new RiskDecision.GateRequired("irreversible action", true));

        service.evaluate(workChannelId, "finance-agent", "Book non-refundable flight.");

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(hookClient).invoke(anyString(), anyString(), anyString(), anyInt(), urlCaptor.capture());
        assertThat(urlCaptor.getValue()).startsWith("http://casehub/openclaw/delivery/oversight/");
    }

    @Test
    void evaluate_gateRequired_oversightAgentIdDefaultsToWorkAgent() {
        when(actionRiskClassifier.classify(any()))
                .thenReturn(new RiskDecision.GateRequired("risk", true));

        service.evaluate(workChannelId, "finance-agent", "Do thing.");

        ArgumentCaptor<String> agentCaptor = ArgumentCaptor.forClass(String.class);
        verify(hookClient).invoke(agentCaptor.capture(), anyString(), anyString(), anyInt(), anyString());
        assertThat(agentCaptor.getValue()).isEqualTo("finance-agent");
    }

    @Test
    void evaluate_oversightChannelAbsent_failsOpen() {
        when(channelService.findByName("case-" + caseId + "/oversight")).thenReturn(Optional.empty());
        when(actionRiskClassifier.classify(any()))
                .thenReturn(new RiskDecision.GateRequired("risk", false));

        assertThatCode(() -> service.evaluate(workChannelId, "agent", "action")).doesNotThrowAnyException();
        verify(messageService, never()).dispatch(any());
    }

    @Test
    void evaluate_classifierThrows_failsOpen() {
        when(actionRiskClassifier.classify(any())).thenThrow(new RuntimeException("classifier error"));

        assertThatCode(() -> service.evaluate(workChannelId, "agent", "action")).doesNotThrowAnyException();
        verify(messageService, never()).dispatch(any());
    }

    // ── fulfill() ─────────────────────────────────────────────────────────────

    private Commitment commitment(UUID gateId) {
        Commitment c = new Commitment();
        c.channelId = oversightChannelId;
        c.correlationId = gateId.toString();
        return c;
    }

    @Test
    void fulfill_approved_dispatchesResponseToOversightAndDoneToWork() {
        UUID gateId = UUID.randomUUID();
        when(commitmentStore.findByCorrelationId(gateId.toString()))
                .thenReturn(Optional.of(commitment(gateId)));

        service.fulfill(gateId, "approved");

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService, org.mockito.Mockito.times(2)).dispatch(captor.capture());

        MessageDispatch oversight = captor.getAllValues().get(0);
        assertThat(oversight.channelId()).isEqualTo(oversightChannelId);
        assertThat(oversight.type()).isEqualTo(MessageType.RESPONSE);
        assertThat(oversight.correlationId()).isEqualTo(gateId.toString());
        assertThat(oversight.sender()).isEqualTo("openclaw-gate");

        MessageDispatch work = captor.getAllValues().get(1);
        assertThat(work.channelId()).isEqualTo(workChannelId);
        assertThat(work.type()).isEqualTo(MessageType.DONE);
        assertThat(work.sender()).isEqualTo("openclaw-gate");
    }

    @Test
    void fulfill_rejected_dispatchesDeclineToOversightAndDeclineToWork() {
        UUID gateId = UUID.randomUUID();
        when(commitmentStore.findByCorrelationId(gateId.toString()))
                .thenReturn(Optional.of(commitment(gateId)));

        service.fulfill(gateId, "rejected");

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService, org.mockito.Mockito.times(2)).dispatch(captor.capture());

        assertThat(captor.getAllValues().get(0).type()).isEqualTo(MessageType.DECLINE);
        assertThat(captor.getAllValues().get(0).correlationId()).isEqualTo(gateId.toString());
        assertThat(captor.getAllValues().get(1).type()).isEqualTo(MessageType.DECLINE);
        assertThat(captor.getAllValues().get(1).channelId()).isEqualTo(workChannelId);
    }

    @Test
    void fulfill_rawOutputNull_treatsAsRejected() {
        UUID gateId = UUID.randomUUID();
        when(commitmentStore.findByCorrelationId(gateId.toString()))
                .thenReturn(Optional.of(commitment(gateId)));

        service.fulfill(gateId, null);

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService, org.mockito.Mockito.times(2)).dispatch(captor.capture());
        assertThat(captor.getAllValues().get(0).type()).isEqualTo(MessageType.DECLINE);
    }

    @Test
    void fulfill_rawOutputBlank_treatsAsRejected() {
        UUID gateId = UUID.randomUUID();
        when(commitmentStore.findByCorrelationId(gateId.toString()))
                .thenReturn(Optional.of(commitment(gateId)));

        service.fulfill(gateId, "   ");

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService, org.mockito.Mockito.times(2)).dispatch(captor.capture());
        assertThat(captor.getAllValues().get(0).type()).isEqualTo(MessageType.DECLINE);
    }

    @Test
    void fulfill_approvedWithTrailingText_isApproved() {
        UUID gateId = UUID.randomUUID();
        when(commitmentStore.findByCorrelationId(gateId.toString()))
                .thenReturn(Optional.of(commitment(gateId)));

        service.fulfill(gateId, "approved, please go ahead");

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService, org.mockito.Mockito.times(2)).dispatch(captor.capture());
        assertThat(captor.getAllValues().get(0).type()).isEqualTo(MessageType.RESPONSE);
    }

    @Test
    void fulfill_notApprovedPrefix_isRejected() {
        UUID gateId = UUID.randomUUID();
        when(commitmentStore.findByCorrelationId(gateId.toString()))
                .thenReturn(Optional.of(commitment(gateId)));

        service.fulfill(gateId, "not approved");

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService, org.mockito.Mockito.times(2)).dispatch(captor.capture());
        assertThat(captor.getAllValues().get(0).type()).isEqualTo(MessageType.DECLINE);
    }

    @Test
    void fulfill_unknownGateId_failsOpen() {
        when(commitmentStore.findByCorrelationId(anyString())).thenReturn(Optional.empty());

        assertThatCode(() -> service.fulfill(UUID.randomUUID(), "approved")).doesNotThrowAnyException();
        verify(messageService, never()).dispatch(any());
    }
}
```

- [ ] **Run to verify it fails**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn --batch-mode test -pl casehub -Dtest=OversightGateServiceTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: FAIL — `OversightGateService` does not exist.

- [ ] **Implement `OversightGateService`**

```java
// casehub/src/main/java/io/casehub/openclaw/casehub/OversightGateService.java
package io.casehub.openclaw.casehub;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import io.casehub.openclaw.client.OpenClawClientConfig;
import io.casehub.openclaw.client.OpenClawHookClient;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.message.Commitment;
import io.casehub.qhorus.runtime.message.MessageService;
import io.casehub.qhorus.runtime.store.CommitmentStore;

/**
 * Owns the oversight gate lifecycle: classifies agent output, dispatches to the
 * work channel (AUTONOMOUS) or opens a human oversight gate (GATE_REQUIRED).
 *
 * <p>Phase 1: {@link DefaultActionRiskClassifier} always returns AUTONOMOUS, so
 * the gate path is never triggered in production. The gate is fully implemented
 * and integration-tested.
 */
@ApplicationScoped
public class OversightGateService {

    private static final Logger log = Logger.getLogger(OversightGateService.class);
    private static final String GATE_SENDER = "openclaw-gate";

    private final ChannelService channelService;
    private final MessageService messageService;
    private final CommitmentStore commitmentStore;
    private final OpenClawHookClient hookClient;
    private final OpenClawClientConfig clientConfig;
    private final OpenClawCasehubConfig casehubConfig;
    private final SpeechActClassifier speechActClassifier;
    private final ActionRiskClassifier actionRiskClassifier;

    @Inject
    public OversightGateService(ChannelService channelService,
                                 MessageService messageService,
                                 CommitmentStore commitmentStore,
                                 OpenClawHookClient hookClient,
                                 OpenClawClientConfig clientConfig,
                                 OpenClawCasehubConfig casehubConfig,
                                 SpeechActClassifier speechActClassifier,
                                 ActionRiskClassifier actionRiskClassifier) {
        this.channelService = channelService;
        this.messageService = messageService;
        this.commitmentStore = commitmentStore;
        this.hookClient = hookClient;
        this.clientConfig = clientConfig;
        this.casehubConfig = casehubConfig;
        this.speechActClassifier = speechActClassifier;
        this.actionRiskClassifier = actionRiskClassifier;
    }

    /**
     * Classifies the agent output and either dispatches to the work channel (AUTONOMOUS)
     * or opens a human oversight gate (GATE_REQUIRED).
     *
     * <p>Fail-open: any exception is caught and logged. The gate must never propagate
     * exceptions to the delivery resource.
     */
    public void evaluate(UUID workChannelId, String agentId, String output) {
        try {
            Channel workChannel = channelService.findById(workChannelId).orElse(null);
            if (workChannel == null) {
                log.warnf("evaluate() called for unknown workChannelId=%s — failing open", workChannelId);
                return;
            }

            UUID caseId = CaseChannelNames.extractCaseId(workChannel.name);
            if (caseId == null) {
                log.warnf("Could not extract caseId from channel name '%s' — failing open", workChannel.name);
                return;
            }

            MessageType messageType = speechActClassifier.classify(
                    new SpeechActContext(agentId, output, null));

            RiskDecision decision = actionRiskClassifier.classify(
                    new PlannedAction(agentId, caseId, output, null, Map.of()));

            if (decision instanceof RiskDecision.Autonomous) {
                messageService.dispatch(MessageDispatch.builder()
                        .channelId(workChannelId)
                        .sender(agentId)
                        .type(messageType)
                        .content(output != null ? output : "")
                        .actorType(ActorType.AGENT)
                        .build());
                return;
            }

            RiskDecision.GateRequired gate = (RiskDecision.GateRequired) decision;
            openGate(caseId, workChannelId, agentId, output, gate);

        } catch (Exception e) {
            log.errorf("OversightGateService.evaluate() failed for channel=%s agent=%s: %s",
                    workChannelId, agentId, e.getMessage());
        }
    }

    private void openGate(UUID caseId, UUID workChannelId, String agentId,
                          String output, RiskDecision.GateRequired gate) {
        Channel oversightChannel = channelService.findByName(
                CaseChannelNames.oversightChannelName(caseId)).orElse(null);
        if (oversightChannel == null) {
            log.errorf("Oversight channel not found for caseId=%s — cannot open gate; failing open. " +
                    "Oversight channel should be created by OpenClawCaseChannelProvider.openChannel().", caseId);
            return;
        }

        UUID gateId = UUID.randomUUID();
        String oversightPrompt = buildOversightPrompt(agentId, output, gate);

        messageService.dispatch(MessageDispatch.builder()
                .channelId(oversightChannel.id)
                .sender(agentId)
                .type(MessageType.COMMAND)
                .content(output != null ? output : "")
                .correlationId(gateId.toString())
                .actorType(ActorType.AGENT)
                .build());

        String oversightDeliveryUrl =
                clientConfig.delivery().baseUrl() + "/openclaw/delivery/oversight/" + gateId;
        String oversightAgentId = casehubConfig.oversight().agentId();
        if (oversightAgentId == null || oversightAgentId.isBlank()) {
            oversightAgentId = agentId;
        }

        hookClient.invoke(oversightAgentId, oversightPrompt,
                clientConfig.agent().defaultModel(),
                clientConfig.agent().defaultTimeoutSeconds(),
                oversightDeliveryUrl);

        log.infof("Oversight gate opened: gateId=%s caseId=%s agent=%s reason=%s",
                gateId, caseId, agentId, gate.reason());
    }

    private String buildOversightPrompt(String agentId, String output, RiskDecision.GateRequired gate) {
        StringBuilder sb = new StringBuilder();
        sb.append("OpenClaw agent \"").append(agentId).append("\" proposes the following action:\n\n");
        sb.append(output != null ? output : "(no output)").append("\n\n");
        sb.append("Reason for oversight: ").append(gate.reason()).append("\n");
        if (!gate.reversible()) {
            sb.append("⚠️ This action cannot be undone once approved.\n");
        }
        sb.append("\nReply with \"approved\" to proceed or \"rejected\" to decline.");
        return sb.toString();
    }

    /**
     * Processes the human's response to a gate. Fulfills or declines the oversight
     * Commitment and dispatches DONE or DECLINE to the work channel.
     *
     * <p>Fail-open: if the gate cannot be found or any error occurs, logs and returns.
     */
    public void fulfill(UUID gateId, String rawOutput) {
        boolean approved = parseApproval(gateId, rawOutput);

        Optional<Commitment> commitmentOpt = commitmentStore.findByCorrelationId(gateId.toString());
        if (commitmentOpt.isEmpty()) {
            log.warnf("fulfill() called for unknown gateId=%s — possible duplicate delivery, ignoring", gateId);
            return;
        }

        Commitment commitment = commitmentOpt.get();
        Channel oversightChannel = channelService.findById(commitment.channelId).orElse(null);
        if (oversightChannel == null) {
            log.errorf("Oversight channel %s not found for gateId=%s — failing open", commitment.channelId, gateId);
            return;
        }

        UUID caseId = CaseChannelNames.extractCaseId(oversightChannel.name);
        if (caseId == null) {
            log.errorf("Could not extract caseId from oversight channel '%s' for gateId=%s — failing open",
                    oversightChannel.name, gateId);
            return;
        }

        Channel workChannel = channelService.findByName(CaseChannelNames.workChannelName(caseId)).orElse(null);
        if (workChannel == null) {
            log.errorf("Work channel not found for caseId=%s gateId=%s — failing open", caseId, gateId);
            return;
        }

        if (approved) {
            messageService.dispatch(MessageDispatch.builder()
                    .channelId(oversightChannel.id)
                    .sender(GATE_SENDER)
                    .type(MessageType.RESPONSE)
                    .content(rawOutput != null ? rawOutput : "approved")
                    .correlationId(gateId.toString())
                    .actorType(ActorType.AGENT)
                    .build());
            messageService.dispatch(MessageDispatch.builder()
                    .channelId(workChannel.id)
                    .sender(GATE_SENDER)
                    .type(MessageType.DONE)
                    .content("")
                    .actorType(ActorType.AGENT)
                    .build());
            log.infof("Gate approved: gateId=%s caseId=%s", gateId, caseId);
        } else {
            messageService.dispatch(MessageDispatch.builder()
                    .channelId(oversightChannel.id)
                    .sender(GATE_SENDER)
                    .type(MessageType.DECLINE)
                    .content(rawOutput != null ? rawOutput : "rejected")
                    .correlationId(gateId.toString())
                    .actorType(ActorType.AGENT)
                    .build());
            messageService.dispatch(MessageDispatch.builder()
                    .channelId(workChannel.id)
                    .sender(GATE_SENDER)
                    .type(MessageType.DECLINE)
                    .content("Human rejected the proposed action via oversight gate")
                    .actorType(ActorType.AGENT)
                    .build());
            log.infof("Gate rejected: gateId=%s caseId=%s", gateId, caseId);
        }
    }

    private boolean parseApproval(UUID gateId, String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) {
            log.warnf("fulfill() received null/blank output for gateId=%s — treating as rejected", gateId);
            return false;
        }
        return rawOutput.trim().toLowerCase().split("\\s+")[0].equals("approved");
    }
}
```

- [ ] **Run all tests in casehub module**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn --batch-mode test -pl casehub -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: All green.

- [ ] **Commit**

```bash
git -C /Users/mdproctor/claude/casehub/openclaw add casehub/src/
git -C /Users/mdproctor/claude/casehub/openclaw commit -m "feat(casehub): OversightGateService — evaluate() and fulfill()

Refs #6"
```

---

## Task 7: Wire changes — `CaseChannelProvider`, `DeliveryResource`

**Files:**
- Modify: `casehub/src/main/java/io/casehub/openclaw/casehub/OpenClawCaseChannelProvider.java`
- Modify: `app/src/main/java/io/casehub/openclaw/app/OpenClawDeliveryResource.java`
- Modify: `app/src/test/java/io/casehub/openclaw/app/OpenClawDeliveryResourceTest.java`

- [ ] **Update oversight channel `allowedTypes` to null in `OpenClawCaseChannelProvider`**

In `OpenClawCaseChannelProvider.java`, find the `LAYOUT` map and change the oversight entry:

```java
// Before:
"oversight", new String[]{"Human governance — agent QUERY and human COMMAND", "COMMAND,QUERY"}

// After:
"oversight", new String[]{"Human governance — agent actions pending human approval", null}
```

Add a comment above the LAYOUT field:
```java
// oversight allowedTypes: null (unrestricted). Minimum types used by the gate mechanism:
// COMMAND (gate request), RESPONSE (approved), DECLINE (rejected). Null used because
// the oversight conversation may also need QUERY, STATUS, and EVENT as use cases evolve.
// Pending casehubio/claudony#142 — update to explicit list if Claudony's design resolution constrains it.
private static final Map<String, String[]> LAYOUT = Map.of(
```

- [ ] **Update `OpenClawDeliveryResource` to delegate to `OversightGateService`**

Replace the entire resource body:

```java
// app/src/main/java/io/casehub/openclaw/app/OpenClawDeliveryResource.java
package io.casehub.openclaw.app;

import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.jboss.logging.Logger;

import io.casehub.openclaw.casehub.OversightGateService;
import io.casehub.qhorus.runtime.channel.ChannelService;

/**
 * Receives OpenClaw agent results delivered via deliver:webhook.
 *
 * <p>Stays thin: validates channelId, confirms channel exists, delegates to
 * {@link OversightGateService#evaluate(UUID, String, String)} which owns classification
 * and gate logic. Always returns 200 on processing failures — OpenClaw must not retry.
 *
 * <p>Phase 1 speech act classification: always DONE. See openclaw#10 for graduation plan.
 * No auth — follows gateway topology (Claudony is the auth entry point). See
 * auth-retrofit-readiness.md protocol.
 */
@Path("/openclaw/delivery/channel")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class OpenClawDeliveryResource {

    private static final Logger log = Logger.getLogger(OpenClawDeliveryResource.class);

    @Inject
    ChannelService channelService;

    @Inject
    OversightGateService oversightGateService;

    @POST
    @Path("/{channelId}")
    public Response deliver(@PathParam("channelId") String channelIdStr,
                             OpenClawDeliveryPayload payload) {
        UUID channelId;
        try {
            channelId = UUID.fromString(channelIdStr);
        } catch (IllegalArgumentException e) {
            return Response.status(400).build();
        }

        if (channelService.findById(channelId).isEmpty()) {
            log.warnf("Delivery received for unknown channelId=%s", channelId);
            return Response.status(404).build();
        }

        String agentId = payload != null && payload.agentId() != null ? payload.agentId() : "openclaw-agent";
        String output = payload != null && payload.output() != null ? payload.output() : "";

        oversightGateService.evaluate(channelId, agentId, output);

        return Response.ok().build();
    }
}
```

- [ ] **Build the app module to check wiring compiles**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn --batch-mode install -pl app -am -DskipTests
```
Expected: BUILD SUCCESS.

- [ ] **Run existing app tests to verify no regression**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn --batch-mode test -pl app -am -Dtest=OpenClawDeliveryResourceTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS (existing 404 and 400 tests still work).

- [ ] **Commit**

```bash
git -C /Users/mdproctor/claude/casehub/openclaw add casehub/src/ app/src/
git -C /Users/mdproctor/claude/casehub/openclaw commit -m "feat: wire OversightGateService into DeliveryResource; oversight channel allowedTypes=null

Refs #6"
```

---

## Task 8: `OpenClawOversightDeliveryResource`

**Files:**
- Create: `app/src/main/java/io/casehub/openclaw/app/OpenClawOversightDeliveryPayload.java`
- Create: `app/src/main/java/io/casehub/openclaw/app/OpenClawOversightDeliveryResource.java`
- Create: `app/src/test/java/io/casehub/openclaw/app/OpenClawOversightDeliveryResourceTest.java`

- [ ] **Write the failing tests**

```java
// app/src/test/java/io/casehub/openclaw/app/OpenClawOversightDeliveryResourceTest.java
package io.casehub.openclaw.app;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;

@QuarkusTest
class OpenClawOversightDeliveryResourceTest {

    @Test
    void deliver_validGateId_returns200() {
        given()
            .contentType(JSON)
            .body("""
                    {"agentId": "finance-agent", "output": "approved"}
                    """)
        .when()
            .post("/openclaw/delivery/oversight/" + UUID.randomUUID())
        .then()
            .statusCode(200);
    }

    @Test
    void deliver_invalidGateId_returns400() {
        given()
            .contentType(JSON)
            .body("""
                    {"agentId": "finance-agent", "output": "approved"}
                    """)
        .when()
            .post("/openclaw/delivery/oversight/not-a-uuid")
        .then()
            .statusCode(400);
    }

    @Test
    void deliver_unknownGateId_returns200() {
        // OversightGateService.fulfill() fails-open when commitment not found
        given()
            .contentType(JSON)
            .body("""
                    {"agentId": "finance-agent", "output": "rejected"}
                    """)
        .when()
            .post("/openclaw/delivery/oversight/" + UUID.randomUUID())
        .then()
            .statusCode(200);
    }
}
```

- [ ] **Run to verify it fails**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn --batch-mode test -pl app -am -Dtest=OpenClawOversightDeliveryResourceTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: FAIL — endpoint does not exist (404 on all paths).

- [ ] **Implement `OpenClawOversightDeliveryPayload`**

```java
// app/src/main/java/io/casehub/openclaw/app/OpenClawOversightDeliveryPayload.java
package io.casehub.openclaw.app;

/**
 * Webhook payload received from OpenClaw when a human responds to an oversight gate
 * question via messaging platform (WhatsApp, Telegram, etc.).
 *
 * <p>Structurally identical to {@link OpenClawDeliveryPayload} today, but kept separate:
 * these represent semantically different events (agent task result vs. human governance
 * decision) and are expected to diverge as oversight responses gain delivery platform
 * metadata (channel, responder identity, timestamp).
 *
 * <p>WARNING: Field names assumed camelCase — verify against live OpenClaw API. See openclaw#11.
 */
public record OpenClawOversightDeliveryPayload(
        String agentId,
        String output
) {}
```

- [ ] **Implement `OpenClawOversightDeliveryResource`**

```java
// app/src/main/java/io/casehub/openclaw/app/OpenClawOversightDeliveryResource.java
package io.casehub.openclaw.app;

import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.jboss.logging.Logger;

import io.casehub.openclaw.casehub.OversightGateService;

/**
 * Receives human responses to oversight gate questions, delivered by OpenClaw
 * after the human replies on WhatsApp, Telegram, or another messaging platform.
 *
 * <p>Stays thin: validates gateId, delegates to {@link OversightGateService#fulfill(UUID, String)}.
 * Always returns 200 — OpenClaw must not retry oversight deliveries.
 */
@Path("/openclaw/delivery/oversight")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class OpenClawOversightDeliveryResource {

    private static final Logger log = Logger.getLogger(OpenClawOversightDeliveryResource.class);

    @Inject
    OversightGateService oversightGateService;

    @POST
    @Path("/{gateId}")
    public Response deliver(@PathParam("gateId") String gateIdStr,
                             OpenClawOversightDeliveryPayload payload) {
        UUID gateId;
        try {
            gateId = UUID.fromString(gateIdStr);
        } catch (IllegalArgumentException e) {
            return Response.status(400).build();
        }

        String rawOutput = payload != null ? payload.output() : null;
        oversightGateService.fulfill(gateId, rawOutput);

        return Response.ok().build();
    }
}
```

- [ ] **Run oversight delivery resource tests**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn --batch-mode test -pl app -am -Dtest=OpenClawOversightDeliveryResourceTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: All green.

- [ ] **Commit**

```bash
git -C /Users/mdproctor/claude/casehub/openclaw add app/src/
git -C /Users/mdproctor/claude/casehub/openclaw commit -m "feat(app): OpenClawOversightDeliveryResource — POST /openclaw/delivery/oversight/{gateId}

Refs #6"
```

---

## Task 9: `BidirectionalWiringIT` — end-to-end integration tests

**Files:**
- Create: `app/src/test/java/io/casehub/openclaw/app/BidirectionalWiringIT.java`

This test is the primary Epic 6 deliverable. It proves the full Qhorus ↔ OpenClaw round trip.

**How it works:** `@QuarkusTest` loads the full CDI context including real Qhorus beans backed by InMemory stores (from `casehub-qhorus-testing`). `@InjectMock` replaces `OpenClawGatewayClient` (the REST client) and `ActionRiskClassifier` with Mockito mocks. We inject real Qhorus services to set up channels and read dispatched messages.

- [ ] **Write the tests**

```java
// app/src/test/java/io/casehub/openclaw/app/BidirectionalWiringIT.java
package io.casehub.openclaw.app;

import java.util.List;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.casehub.openclaw.casehub.ActionRiskClassifier;
import io.casehub.openclaw.casehub.CaseChannelNames;
import io.casehub.openclaw.casehub.OpenClawAgentRegistry;
import io.casehub.openclaw.casehub.OpenClawChannelBackend;
import io.casehub.openclaw.casehub.PlannedAction;
import io.casehub.openclaw.casehub.RiskDecision;
import io.casehub.openclaw.client.AgentInvocationRequest;
import io.casehub.openclaw.client.OpenClawGatewayClient;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.gateway.ChannelInitialisedEvent;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.message.Message;
import io.casehub.qhorus.runtime.message.MessageService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class BidirectionalWiringIT {

    @Inject ChannelService channelService;
    @Inject MessageService messageService;
    @Inject OpenClawAgentRegistry registry;
    @Inject OpenClawChannelBackend backend;

    @InjectMock OpenClawGatewayClient gatewayClient;
    @InjectMock ActionRiskClassifier actionRiskClassifier;

    UUID caseId;
    UUID workChannelId;
    UUID oversightChannelId;

    @BeforeEach
    void setup() {
        caseId = UUID.randomUUID();

        Channel work = channelService.create(
                CaseChannelNames.workChannelName(caseId),
                "Work channel", ChannelSemantic.APPEND,
                null, null, null, null, null, null);
        workChannelId = work.id;

        Channel oversight = channelService.create(
                CaseChannelNames.oversightChannelName(caseId),
                "Oversight channel", ChannelSemantic.APPEND,
                null, null, null, null, null, null);
        oversightChannelId = oversight.id;

        registry.register("test-agent", caseId, "test-session-key");
        backend.onChannelInitialised(new ChannelInitialisedEvent(workChannelId, work.name));

        // Default: AUTONOMOUS (gate never fires unless overridden in a specific test)
        when(actionRiskClassifier.classify(any(PlannedAction.class)))
                .thenReturn(new RiskDecision.Autonomous());

        // OpenClaw gateway: return 200 for all invocations
        Response okResponse = mock(Response.class);
        when(okResponse.getStatus()).thenReturn(200);
        when(gatewayClient.invokeAgent(any(AgentInvocationRequest.class))).thenReturn(okResponse);
    }

    // ── Autonomous path ────────────────────────────────────────────────────────

    @Test
    void command_on_work_channel_invokes_openclaw_with_correct_body() {
        messageService.dispatch(MessageDispatch.builder()
                .channelId(workChannelId)
                .sender("engine")
                .type(MessageType.COMMAND)
                .content("Analyse this transaction")
                .actorType(ActorType.AGENT)
                .build());

        ArgumentCaptor<AgentInvocationRequest> captor =
                ArgumentCaptor.forClass(AgentInvocationRequest.class);
        verify(gatewayClient).invokeAgent(captor.capture());

        AgentInvocationRequest req = captor.getValue();
        assertThat(req.agentId()).isEqualTo("test-agent");
        assertThat(req.message()).isEqualTo("Analyse this transaction");
        assertThat(req.deliver()).isEqualTo("webhook");
        assertThat(req.to()).contains("/openclaw/delivery/channel/" + workChannelId);
    }

    @Test
    void delivery_webhook_dispatches_done_to_work_channel() {
        // First: trigger the COMMAND so ChannelBackend registers the session
        messageService.dispatch(MessageDispatch.builder()
                .channelId(workChannelId).sender("engine")
                .type(MessageType.COMMAND).content("Analyse this")
                .actorType(ActorType.AGENT).build());

        // Simulate OpenClaw posting back the result
        given()
            .contentType(JSON)
            .body("""
                    {"agentId": "test-agent", "output": "Transaction analysed. No anomalies."}
                    """)
        .when()
            .post("/openclaw/delivery/channel/" + workChannelId)
        .then()
            .statusCode(200);

        // Verify DONE was dispatched to the work channel
        List<Message> messages = messageService.pollAfter(workChannelId, 0L, 20, false);
        assertThat(messages)
                .filteredOn(m -> m.messageType == MessageType.DONE)
                .hasSize(1)
                .first()
                .satisfies(m -> {
                    assertThat(m.content).isEqualTo("Transaction analysed. No anomalies.");
                    assertThat(m.sender).isEqualTo("test-agent");
                });
    }

    // ── Gate required path ─────────────────────────────────────────────────────

    @Test
    void gate_required_invokes_openclaw_with_oversight_delivery_url() {
        when(actionRiskClassifier.classify(any(PlannedAction.class)))
                .thenReturn(new RiskDecision.GateRequired("spending limit exceeded", false));

        messageService.dispatch(MessageDispatch.builder()
                .channelId(workChannelId).sender("engine")
                .type(MessageType.COMMAND).content("Cancel Netflix subscription")
                .actorType(ActorType.AGENT).build());

        // Simulate OpenClaw delivering the task result → gate fires
        given()
            .contentType(JSON)
            .body("""
                    {"agentId": "test-agent", "output": "I will cancel the Netflix subscription."}
                    """)
        .when()
            .post("/openclaw/delivery/channel/" + workChannelId)
        .then()
            .statusCode(200);

        // OpenClaw should have been invoked twice: once for the task, once for oversight delivery
        ArgumentCaptor<AgentInvocationRequest> captor =
                ArgumentCaptor.forClass(AgentInvocationRequest.class);
        verify(gatewayClient, atLeastOnce()).invokeAgent(captor.capture());

        // Find the oversight invocation (the one with oversight URL)
        AgentInvocationRequest oversightInvocation = captor.getAllValues().stream()
                .filter(r -> r.to().contains("/openclaw/delivery/oversight/"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No oversight invocation found"));

        assertThat(oversightInvocation.deliver()).isEqualTo("webhook");
        assertThat(oversightInvocation.message()).contains("I will cancel the Netflix subscription.");
        assertThat(oversightInvocation.message()).contains("spending limit exceeded");
    }

    @Test
    void gate_required_posts_command_to_oversight_channel() {
        when(actionRiskClassifier.classify(any(PlannedAction.class)))
                .thenReturn(new RiskDecision.GateRequired("irreversible", false));

        messageService.dispatch(MessageDispatch.builder()
                .channelId(workChannelId).sender("engine")
                .type(MessageType.COMMAND).content("Delete all records")
                .actorType(ActorType.AGENT).build());

        given()
            .contentType(JSON)
            .body("""
                    {"agentId": "test-agent", "output": "Deleting all records now."}
                    """)
        .when()
            .post("/openclaw/delivery/channel/" + workChannelId)
        .then()
            .statusCode(200);

        List<Message> oversightMessages = messageService.pollAfter(oversightChannelId, 0L, 10, false);
        assertThat(oversightMessages)
                .filteredOn(m -> m.messageType == MessageType.COMMAND)
                .hasSize(1)
                .first()
                .satisfies(m -> assertThat(m.correlationId).isNotNull());
    }

    @Test
    void oversight_approval_dispatches_done_to_work_channel() {
        when(actionRiskClassifier.classify(any(PlannedAction.class)))
                .thenReturn(new RiskDecision.GateRequired("risk", true));

        // Trigger task + gate opening
        messageService.dispatch(MessageDispatch.builder()
                .channelId(workChannelId).sender("engine")
                .type(MessageType.COMMAND).content("Do risky thing")
                .actorType(ActorType.AGENT).build());

        given()
            .contentType(JSON)
            .body("""
                    {"agentId": "test-agent", "output": "I will do the risky thing."}
                    """)
        .when()
            .post("/openclaw/delivery/channel/" + workChannelId)
        .then()
            .statusCode(200);

        // Find the gateId from the oversight channel's COMMAND message
        List<Message> oversightMsgs = messageService.pollAfter(oversightChannelId, 0L, 10, false);
        String gateId = oversightMsgs.stream()
                .filter(m -> m.messageType == MessageType.COMMAND)
                .findFirst()
                .map(m -> m.correlationId)
                .orElseThrow(() -> new AssertionError("No oversight COMMAND found"));

        // Human approves
        given()
            .contentType(JSON)
            .body("""
                    {"agentId": "test-agent", "output": "approved"}
                    """)
        .when()
            .post("/openclaw/delivery/oversight/" + gateId)
        .then()
            .statusCode(200);

        // Oversight channel should have RESPONSE
        List<Message> allOversight = messageService.pollAfter(oversightChannelId, 0L, 20, false);
        assertThat(allOversight)
                .filteredOn(m -> m.messageType == MessageType.RESPONSE)
                .hasSize(1)
                .first()
                .satisfies(m -> assertThat(m.correlationId).isEqualTo(gateId));

        // Work channel should have DONE
        List<Message> workMsgs = messageService.pollAfter(workChannelId, 0L, 20, false);
        assertThat(workMsgs)
                .filteredOn(m -> m.messageType == MessageType.DONE)
                .hasSize(1);
    }

    @Test
    void oversight_rejection_dispatches_decline_to_work_channel() {
        when(actionRiskClassifier.classify(any(PlannedAction.class)))
                .thenReturn(new RiskDecision.GateRequired("risk", false));

        messageService.dispatch(MessageDispatch.builder()
                .channelId(workChannelId).sender("engine")
                .type(MessageType.COMMAND).content("Do thing")
                .actorType(ActorType.AGENT).build());

        given()
            .contentType(JSON)
            .body("""
                    {"agentId": "test-agent", "output": "I will do the thing."}
                    """)
        .when()
            .post("/openclaw/delivery/channel/" + workChannelId)
        .then()
            .statusCode(200);

        String gateId = messageService.pollAfter(oversightChannelId, 0L, 10, false).stream()
                .filter(m -> m.messageType == MessageType.COMMAND)
                .findFirst()
                .map(m -> m.correlationId)
                .orElseThrow(() -> new AssertionError("No oversight COMMAND"));

        // Human rejects
        given()
            .contentType(JSON)
            .body("""
                    {"agentId": "test-agent", "output": "rejected, too risky"}
                    """)
        .when()
            .post("/openclaw/delivery/oversight/" + gateId)
        .then()
            .statusCode(200);

        List<Message> workMsgs = messageService.pollAfter(workChannelId, 0L, 20, false);
        assertThat(workMsgs)
                .filteredOn(m -> m.messageType == MessageType.DECLINE)
                .hasSize(1);
    }

    // ── Error cases ────────────────────────────────────────────────────────────

    @Test
    void deliver_unknown_channel_returns_404() {
        given()
            .contentType(JSON)
            .body("""
                    {"agentId": "test-agent", "output": "done"}
                    """)
        .when()
            .post("/openclaw/delivery/channel/" + UUID.randomUUID())
        .then()
            .statusCode(404);
    }

    @Test
    void deliver_invalid_uuid_returns_400() {
        given()
            .contentType(JSON)
            .body("""
                    {"agentId": "test-agent", "output": "done"}
                    """)
        .when()
            .post("/openclaw/delivery/channel/not-a-uuid")
        .then()
            .statusCode(400);
    }

    @Test
    void oversight_delivery_unknown_gateId_returns_200_failopen() {
        given()
            .contentType(JSON)
            .body("""
                    {"agentId": "test-agent", "output": "approved"}
                    """)
        .when()
            .post("/openclaw/delivery/oversight/" + UUID.randomUUID())
        .then()
            .statusCode(200);
    }
}
```

- [ ] **Run to verify some tests fail** (gateway not yet invokable — check compilation first)

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn --batch-mode test -pl app -am -Dtest=BidirectionalWiringIT -Dsurefire.failIfNoSpecifiedTests=false
```

If compilation fails, fix import errors. If tests fail at runtime, check the error output — common issues:
- `ChannelService.create()` signature mismatch — check the actual parameter count in the qhorus source
- `@InjectMock` on `OpenClawGatewayClient` needs `quarkus-junit-mockito` in app pom (already present)
- Missing test application properties — ensure `application.properties` in `app/src/test/resources/` has `casehub.openclaw.agents.*` configured

- [ ] **Fix any failures before marking complete**

For `ChannelService.create()` signature, check the actual Qhorus API:
```bash
# find the signature
grep -n "create(" /Users/mdproctor/claude/casehub/qhorus/runtime/src/main/java/io/casehub/qhorus/runtime/channel/ChannelService.java | head -5
```

Adjust the test setup call to match the actual signature.

- [ ] **Run full build to confirm everything passes**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn --batch-mode install
```
Expected: BUILD SUCCESS, all tests green.

- [ ] **Commit**

```bash
git -C /Users/mdproctor/claude/casehub/openclaw add app/src/test/
git -C /Users/mdproctor/claude/casehub/openclaw commit -m "test(app): BidirectionalWiringIT — end-to-end round trip and oversight gate

Refs #6"
```

---

## Self-Review Checklist

- [x] **CaseChannelNames** — Task 1 ✅
- [x] **ActionRiskClassifier** (interface + PlannedAction + RiskDecision + DefaultActionRiskClassifier) — Task 2 ✅
- [x] **SpeechActClassifier** (interface + SpeechActContext + DefaultSpeechActClassifier) — Task 3 ✅
- [x] **OpenClawCasehubConfig.Oversight** — Task 4 ✅
- [x] **OpenClawHookClient.invoke() overload** — Task 5 ✅
- [x] **OversightGateService** (evaluate + fulfill) — Task 6 ✅
- [x] **OpenClawCaseChannelProvider oversight allowedTypes → null** — Task 7 ✅
- [x] **OpenClawDeliveryResource delegates to OversightGateService** — Task 7 ✅
- [x] **OpenClawOversightDeliveryResource + Payload** — Task 8 ✅
- [x] **BidirectionalWiringIT** (autonomous + gate + error cases) — Task 9 ✅
- [x] **No placeholders** — all code is complete
- [x] **Type consistency** — `RiskDecision.Autonomous/GateRequired` used consistently; `SpeechActContext/PlannedAction` match their definitions; `OversightGateService` constructor matches usage in tests
- [x] **`parseApproval` is private in OversightGateService** — accessed only within `fulfill()`; approval parsing belongs in the service not the resource ✅
- [x] **openclaw#11 warning preserved** — payload field name comment retained in both payload records ✅
