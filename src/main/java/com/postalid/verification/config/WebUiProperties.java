package com.postalid.verification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Operator accounts for the browser console.
 *
 * <p>Separate from {@link PostalIdProperties} because these are deployment credentials rather
 * than behaviour: they belong in an environment-specific file or a secret store, not in anything
 * committed.
 */
@ConfigurationProperties(prefix = "postalid.web")
public class WebUiProperties {

    private Map<String, Operator> operators = new LinkedHashMap<>();

    /**
     * Whether the OpenAPI document and Swagger UI are readable without signing in.
     *
     * <p>Off by default. The document describes an endpoint over personal data, including its
     * scopes and rate limits, and there is no reason to publish that to anyone who can reach the
     * host. Turn it on for a development instance, or where the API is deliberately public.
     */
    private boolean publicApiDocs = false;

    public Map<String, Operator> getOperators() { return operators; }
    public void setOperators(Map<String, Operator> operators) { this.operators = operators; }

    public boolean isPublicApiDocs() { return publicApiDocs; }
    public void setPublicApiDocs(boolean publicApiDocs) { this.publicApiDocs = publicApiDocs; }

    /**
     * The client id and secret the browser console uses to call the API on the operator's behalf,
     * so the UI goes through exactly the same authorization path as any other client.
     */
    private String apiClientId;
    private String apiClientSecret;

    public String getApiClientId() { return apiClientId; }
    public void setApiClientId(String apiClientId) { this.apiClientId = apiClientId; }

    public String getApiClientSecret() { return apiClientSecret; }
    public void setApiClientSecret(String apiClientSecret) { this.apiClientSecret = apiClientSecret; }

    public static class Operator {
        /** Plaintext only in configuration; hashed with BCrypt before it reaches the user store. */
        private String password;

        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }
}
