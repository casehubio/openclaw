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
