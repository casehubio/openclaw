package io.casehub.openclaw.app;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import io.casehub.api.spi.ProvisionerConfigRegistry;
import io.casehub.engine.common.qualifier.CrossTenant;
import io.casehub.qhorus.api.store.CrossTenantChannelStore;
import io.casehub.qhorus.api.store.CrossTenantMessageStore;
import io.casehub.qhorus.persistence.memory.InMemoryMessageStore;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.quarkus.test.Mock;

import static org.mockito.Mockito.mock;

/**
 * Provides missing test beans to satisfy CDI wiring in @QuarkusTest.
 *
 * <p>@CrossTenant qualified stores and ProvisionerConfigRegistry are not provided
 * by platform-testing or qhorus-testing @Alternative beans. This class provides
 * mocks so Quarkus can start in test mode.
 *
 * <p>TODO openclaw#TBD: Investigate why platform-testing does not provide these;
 * consider moving this to a shared test resource if other casehubio repos have the
 * same gap.
 */
@Mock
@ApplicationScoped
public class TestBeanProvider {

    @Inject InMemoryMessageStore messageStore;
    @Inject ChannelService channelService;

    @Produces
    @CrossTenant
    @ApplicationScoped
    public CrossTenantMessageStore crossTenantMessageStore() {
        return new CrossTenantMessageStore() {
            @Override public java.util.List<io.casehub.qhorus.api.message.Message> scan(io.casehub.qhorus.api.store.query.MessageQuery query) { return messageStore.scan(query); }
            @Override public long count(io.casehub.qhorus.api.store.query.MessageQuery query) { return messageStore.count(query); }
            @Override public int countByChannel(java.util.UUID channelId) { return messageStore.countByChannel(channelId); }
            @Override public java.util.List<String> distinctSendersByChannel(java.util.UUID channelId, io.casehub.qhorus.api.message.MessageType excludedType) { return messageStore.distinctSendersByChannel(channelId, excludedType); }
            @Override public java.util.Optional<io.casehub.qhorus.api.message.Message> findLastMessage(java.util.UUID channelId) { return messageStore.findLastMessage(channelId); }
            @Override public java.util.Optional<io.casehub.qhorus.api.message.Message> find(Long id) { return messageStore.find(id); }
        };
    }

    @Produces
    @CrossTenant
    @ApplicationScoped
    public CrossTenantChannelStore crossTenantChannelStore() {
        return new CrossTenantChannelStore() {
            @Override public java.util.List<io.casehub.qhorus.api.channel.Channel> listAll() { return channelService.listAll(); }
            @Override public java.util.Optional<io.casehub.qhorus.api.channel.Channel> findById(java.util.UUID id) { return channelService.findById(id); }
            @Override public java.util.Optional<io.casehub.qhorus.api.channel.Channel> findByNameAndTenancy(String name, String tenancyId) { return channelService.findByName(name); }
        };
    }

    @Produces
    @ApplicationScoped
    public ProvisionerConfigRegistry provisionerConfigRegistry() {
        return mock(ProvisionerConfigRegistry.class);
    }
}
