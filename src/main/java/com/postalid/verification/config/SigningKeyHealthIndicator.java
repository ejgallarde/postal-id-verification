package com.postalid.verification.config;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Surfaces an ephemeral token-signing key.
 *
 * <p>When no keystore is configured the application generates a key pair at startup. That is fine
 * on a laptop and quietly wrong in production: every issued token dies on restart, and a second
 * instance cannot validate the first's tokens, so a rolling deploy hands clients intermittent 401s
 * with nothing in the logs to explain them.
 *
 * <p>A startup warning is easy to miss. Reporting it in health means a monitoring system sees it,
 * and someone can catch the misconfiguration before it becomes a support ticket.
 *
 * <p>Deliberately {@code UP} rather than {@code DOWN}: the service genuinely works, so failing the
 * probe would take a functioning instance out of rotation. It carries a status detail instead.
 */
// Bean name signingKeyHealthIndicator yields the health key "signingKey".
@Component
public class SigningKeyHealthIndicator implements HealthIndicator {

    private final boolean ephemeral;

    public SigningKeyHealthIndicator(PostalIdProperties properties) {
        String keystore = properties.getOauth().getJwk().getKeystoreLocation();
        this.ephemeral = keystore == null || keystore.isBlank();
    }

    @Override
    public Health health() {
        if (ephemeral) {
            return Health.up()
                    .withDetail("source", "ephemeral")
                    .withDetail("warning", "No JWT keystore configured. Tokens are invalidated by "
                            + "every restart and cannot be validated across instances. Set "
                            + "postalid.oauth.jwk.keystore-location before deploying.")
                    .build();
        }
        return Health.up().withDetail("source", "keystore").build();
    }
}
