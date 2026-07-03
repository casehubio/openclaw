package io.casehub.openclaw.app;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import io.casehub.api.spi.ProvisionerConfigRegistry;
import io.casehub.engine.common.qualifier.CrossTenant;
import io.casehub.qhorus.api.store.CrossTenantChannelStore;
import io.casehub.qhorus.api.store.CrossTenantMessageStore;
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

    @Produces
    @CrossTenant
    @ApplicationScoped
    public CrossTenantMessageStore crossTenantMessageStore() {
        return mock(CrossTenantMessageStore.class);
    }

    @Produces
    @CrossTenant
    @ApplicationScoped
    public CrossTenantChannelStore crossTenantChannelStore() {
        return mock(CrossTenantChannelStore.class);
    }

    @Produces
    @ApplicationScoped
    public ProvisionerConfigRegistry provisionerConfigRegistry() {
        return mock(ProvisionerConfigRegistry.class);
    }
}
