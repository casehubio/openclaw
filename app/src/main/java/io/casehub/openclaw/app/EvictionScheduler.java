package io.casehub.openclaw.app;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.casehub.openclaw.context.ChannelContextWindowService;
import io.quarkus.scheduler.Scheduled;

/**
 * Eagerly evicts TTL-expired entries from ChannelContextWindow ring buffers.
 *
 * <p>Lives in app/ rather than core/ to keep quarkus-scheduler off the library module.
 *
 * <p>Runs at the TTL interval. In the worst case, expired entries may remain in memory for
 * up to one additional TTL period beyond their expiry time. This is acceptable because
 * {@link io.casehub.openclaw.context.ChannelRingBuffer#query} applies TTL filtering
 * on every call — expired entries are never returned to callers. Eager eviction reclaims
 * memory; it does not affect correctness.
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
