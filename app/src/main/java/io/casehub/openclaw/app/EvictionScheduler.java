package io.casehub.openclaw.app;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.casehub.openclaw.context.ChannelContextWindowService;
import io.quarkus.scheduler.Scheduled;

/**
 * Eagerly evicts TTL-expired entries from ChannelContextWindow ring buffers.
 *
 * <p>Lives in app/ rather than core/ to keep quarkus-scheduler off the library module.
 * Runs at the TTL interval — not a separate config to avoid the misconfiguration risk
 * of an eviction interval that exceeds the TTL.
 */
@ApplicationScoped
public class EvictionScheduler {

    @Inject
    ChannelContextWindowService service;

    @Scheduled(every = "${casehub.openclaw.context-window.ttl:PT30M}")
    void evict() {
        service.evictExpired();
    }
}
