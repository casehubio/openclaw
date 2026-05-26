package io.casehub.openclaw.client;

public class OpenClawInvocationException extends RuntimeException {

    public OpenClawInvocationException(String message) {
        super(message);
    }

    public OpenClawInvocationException(String message, Throwable cause) {
        super(message, cause);
    }
}
