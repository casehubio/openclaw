package io.casehub.openclaw.app;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import io.quarkus.arc.profile.IfBuildProfile;

import io.casehub.api.spi.ProvisionerConfigRegistry;
import io.casehub.engine.common.qualifier.CrossTenant;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.message.Message;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.store.CrossTenantChannelStore;
import io.casehub.qhorus.api.store.CrossTenantMessageStore;
import io.casehub.qhorus.api.store.query.MessageQuery;

/**
 * Dev-mode-only bean provider for CDI dependencies normally supplied by the
 * full platform stack. Enables {@code quarkus:dev} without Keycloak, platform
 * runtime, or cross-tenant infrastructure.
 */
@IfBuildProfile("dev")
@ApplicationScoped
public class DevBeanProvider {

    @Produces
    @CrossTenant
    @ApplicationScoped
    public CrossTenantMessageStore crossTenantMessageStore() {
        return new CrossTenantMessageStore() {
            @Override public List<Message> scan(MessageQuery query) { return List.of(); }
            @Override public long count(MessageQuery query) { return 0; }
            @Override public int countByChannel(UUID channelId) { return 0; }
            @Override public List<String> distinctSendersByChannel(UUID channelId, MessageType excludedType) { return List.of(); }
            @Override public Optional<Message> findLastMessage(UUID channelId) { return Optional.empty(); }
            @Override public Optional<Message> find(Long id) { return Optional.empty(); }
        };
    }

    @Produces
    @CrossTenant
    @ApplicationScoped
    public CrossTenantChannelStore crossTenantChannelStore() {
        return new CrossTenantChannelStore() {
            @Override public List<Channel> listAll() { return List.of(); }
            @Override public Optional<Channel> findById(UUID id) { return Optional.empty(); }
            @Override public Optional<Channel> findByNameAndTenancy(String name, String tenancyId) { return Optional.empty(); }
        };
    }

    @Produces
    @ApplicationScoped
    public ProvisionerConfigRegistry provisionerConfigRegistry() {
        return new ProvisionerConfigRegistry() {
            @Override public Map<String, Object> configFor(String providerName, String agentId) { return Map.of(); }
            @Override public Set<String> declaredAgentIds(String providerName) { return Set.of(); }
        };
    }
}
