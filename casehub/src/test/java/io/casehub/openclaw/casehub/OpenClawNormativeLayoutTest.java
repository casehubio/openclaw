package io.casehub.openclaw.casehub;

import java.util.Set;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.message.MessageType;

import static org.assertj.core.api.Assertions.assertThat;

class OpenClawNormativeLayoutTest {

    @Test
    void layout_containsExactlyThreePurposes() {
        assertThat(OpenClawNormativeLayout.LAYOUT.keySet())
                .containsExactlyInAnyOrder("work", "observe", "oversight");
    }

    @Test
    void layout_work_isUnrestricted() {
        OpenClawNormativeLayout.ChannelSpec spec = OpenClawNormativeLayout.LAYOUT.get("work");
        assertThat(spec).isNotNull();
        assertThat(spec.allowedTypes()).isNull();
        assertThat(spec.deniedTypes()).isNull();
    }

    @Test
    void layout_observe_allowsOnlyEvent() {
        OpenClawNormativeLayout.ChannelSpec spec = OpenClawNormativeLayout.LAYOUT.get("observe");
        assertThat(spec).isNotNull();
        assertThat(spec.allowedTypes()).isEqualTo(Set.of(MessageType.EVENT));
        assertThat(spec.deniedTypes()).isNull();
    }

    @Test
    void layout_oversight_deniesEvent() {
        OpenClawNormativeLayout.ChannelSpec spec = OpenClawNormativeLayout.LAYOUT.get("oversight");
        assertThat(spec).isNotNull();
        assertThat(spec.allowedTypes()).isNull();
        assertThat(spec.deniedTypes()).isEqualTo(Set.of(MessageType.EVENT));
    }
}
