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
    void extractCaseId_nullInput_returnsNull() {
        assertThat(CaseChannelNames.extractCaseId(null)).isNull();
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
