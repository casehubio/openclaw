package io.casehub.openclaw.casehub;

import jakarta.enterprise.context.ApplicationScoped;

import org.jboss.logging.Logger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class DirectCallBridge {

    private static final Logger log = Logger.getLogger(DirectCallBridge.class);

    private final ConcurrentHashMap<String, CompletableFuture<String>> futures =
            new ConcurrentHashMap<>();

    public CompletableFuture<String> submit(String correlationId) {
        CompletableFuture<String> future = new CompletableFuture<>();
        CompletableFuture<String> existing = futures.putIfAbsent(correlationId, future);
        if (existing != null) {
            log.warnf("Duplicate correlationId=%s — returning existing future", correlationId);
            return existing;
        }
        return future;
    }

    public void complete(String correlationId, String responseText) {
        CompletableFuture<String> future = futures.remove(correlationId);
        if (future != null) {
            future.complete(responseText);
        }
    }

    public void cancel(String correlationId) {
        CompletableFuture<String> future = futures.remove(correlationId);
        if (future != null) {
            future.cancel(true);
        }
    }
}
