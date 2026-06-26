package io.casehub.openclaw.casehub;

import java.util.List;
import java.util.Map;

public interface AgentProviderConfigSource {
    record AgentConfig(String sessionKey, List<String> capabilities) {}
    Map<String, AgentConfig> allAgents();
}
