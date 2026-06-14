package io.casehub.openclaw.casehub;

import java.util.Map;
import java.util.Set;

import io.casehub.qhorus.api.message.MessageType;

/**
 * Normative 3-channel layout for CaseHub/OpenClaw integration.
 * Source of truth: PLATFORM.md §Agent Communication Mesh.
 * Consolidation of NormativeChannelLayout: parent#93.
 */
final class OpenClawNormativeLayout {

    /**
     * @param allowedTypes message types permitted on this channel; null = unrestricted
     * @param deniedTypes  message types blocked on this channel; null = unrestricted
     */
    record ChannelSpec(
            String description,
            Set<MessageType> allowedTypes,
            Set<MessageType> deniedTypes
    ) {}

    static final Map<String, ChannelSpec> LAYOUT = Map.of(
            "work",     new ChannelSpec(
                    "Primary coordination — all obligation-carrying message types", null, null),
            "observe",  new ChannelSpec(
                    "Telemetry — EVENT only, no obligations created", Set.of(MessageType.EVENT), null),
            "oversight",new ChannelSpec(
                    "Human governance — agent actions pending human approval", null, Set.of(MessageType.EVENT))
    );

    private OpenClawNormativeLayout() {}
}
