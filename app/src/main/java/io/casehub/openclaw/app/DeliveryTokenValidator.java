package io.casehub.openclaw.app;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class DeliveryTokenValidator {

    private final byte[] tokenBytes;

    @Inject
    public DeliveryTokenValidator(
            @ConfigProperty(name = "casehub.openclaw.delivery.token", defaultValue = "")
            String token) {
        this.tokenBytes = token.isBlank() ? null : token.getBytes(StandardCharsets.UTF_8);
    }

    public boolean isEnabled() {
        return tokenBytes != null;
    }

    public boolean isValid(String token) {
        if (tokenBytes == null) return true;
        if (token == null) return false;
        return MessageDigest.isEqual(tokenBytes, token.getBytes(StandardCharsets.UTF_8));
    }
}
