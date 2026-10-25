package com.postalid.verification.api;

/**
 * The {@code include} query parameter named something this API cannot embed. Mapped to a 400
 * validation problem naming the parameter, the same shape as a bad body field.
 */
public class UnsupportedIncludeException extends RuntimeException {

    private final String supported;

    public UnsupportedIncludeException(String supported) {
        super("Unsupported include value");
        this.supported = supported;
    }

    /** The accepted values, for the error message. Never echoes what the caller sent. */
    public String getSupported() {
        return supported;
    }
}
