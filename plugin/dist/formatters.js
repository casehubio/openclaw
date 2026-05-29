export function formatMessages(messages) {
    return messages.map((m) => {
        const channel = m.channelName ?? m.channelId;
        const time = new Date(m.receivedAt).toISOString().slice(11, 16) + "Z"; // UTC HH:MMZ
        return `**${m.senderId}** on \`${channel}\` [${m.messageType}] at ${time}:\n${m.content}`;
    }).join("\n\n");
}
export function formatIdle(lastChannelActivity) {
    // Use Date.parse() rather than string comparison — Jackson may serialise Instant.EPOCH
    // as "1970-01-01T00:00:00Z" or "1970-01-01T00:00:00.000Z" depending on version.
    // Date.parse() returns 0 for epoch regardless of millisecond precision.
    const ts = Date.parse(lastChannelActivity);
    if (ts === 0)
        return "No channel activity recorded for this agent yet.";
    const elapsedMin = Math.floor((Date.now() - ts) / 60_000);
    return `No channel activity in the last ${elapsedMin} minute(s).`;
}
//# sourceMappingURL=formatters.js.map