package io.casehub.openclaw.casehub;

import java.util.UUID;

record GateContext(String originalCommitmentId, UUID workChannelId,
                   long commandMessageId, String tenancyId) {}
