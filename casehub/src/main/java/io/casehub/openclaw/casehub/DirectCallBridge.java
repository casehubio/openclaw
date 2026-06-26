package io.casehub.openclaw.casehub;

import jakarta.enterprise.context.ApplicationScoped;

import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class DirectCallBridge {

    private static final Logger log = Logger.getLogger(DirectCallBridge.class);

    private final ConcurrentHashMap<String, CompletableFuture<String>> futures =
            new ConcurrentHashMap<>();

    public CompletableFuture<String> submit(String correlationId, Duration timeout) {
        CompletableFuture<String> future = new CompletableFuture<>();
        CompletableFuture<String> existing = futures.putIfAbsent(correlationId, future);
        if (existing != null) {
            log.warnf("Duplicate correlationId=%s — returning existing future", correlationId);
            return existing;
        }
        future.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
        future.whenComplete((result, error) -> futures.remove(correlationId));
        return future;
    }

    public void complete(String correlationId, String responseText) {
        CompletableFuture<String> future = futures.get(correlationId);
        if (future != null) {
            future.complete(responseText);
        }
    }

    public void cancel(String correlationId) {
        CompletableFuture<String> future = futures.get(correlationId);
        if (future != null) {
            future.cancel(true);
        }
    }
}
