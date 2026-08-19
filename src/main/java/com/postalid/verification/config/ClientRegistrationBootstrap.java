package com.postalid.verification.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;

import java.util.Map;
import java.util.UUID;

/**
 * Brings the registered API clients in line with configuration at startup.
 *
 * <p>Clients are declared under {@code postalid.oauth.clients}, with secrets supplied per
 * environment — an environment variable in a real deployment, never a literal in a committed
 * file. Secrets are BCrypt-hashed on the way into the database, so the stored form cannot be
 * replayed as a credential.
 *
 * <p>Declaring no clients is a valid production posture: rows can be managed directly instead.
 */
@Configuration
public class ClientRegistrationBootstrap {

    private static final Logger log = LoggerFactory.getLogger(ClientRegistrationBootstrap.class);

    @Bean
    public ApplicationRunner registerConfiguredClients(RegisteredClientRepository repository,
                                                       PasswordEncoder passwordEncoder,
                                                       PostalIdProperties properties) {
        return args -> {
            Map<String, PostalIdProperties.Oauth.Client> clients = properties.getOauth().getClients();
            if (clients.isEmpty()) {
                log.info("No OAuth clients declared in configuration; leaving oauth2_registered_client as-is.");
                return;
            }

            clients.forEach((clientId, client) -> {
                if (client.getSecret() == null || client.getSecret().isBlank()) {
                    throw new IllegalStateException(
                            "OAuth client '" + clientId + "' has no secret configured.");
                }
                if (client.getScopes().isEmpty()) {
                    throw new IllegalStateException(
                            "OAuth client '" + clientId + "' has no scopes configured.");
                }

                RegisteredClient existing = repository.findByClientId(clientId);
                String id = existing != null ? existing.getId() : UUID.randomUUID().toString();

                RegisteredClient.Builder builder = RegisteredClient.withId(id)
                        .clientId(clientId)
                        .clientName(client.getName() != null ? client.getName() : clientId)
                        .clientSecret(passwordEncoder.encode(client.getSecret()))
                        // HTTP Basic is the spec's preferred form; the POST body variant is
                        // accepted because a good many HTTP clients only do that one.
                        .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                        .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                        .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                        .clientSettings(ClientSettings.builder()
                                .requireAuthorizationConsent(false)
                                .build())
                        .tokenSettings(TokenSettings.builder()
                                .accessTokenFormat(OAuth2TokenFormat.SELF_CONTAINED)
                                .accessTokenTimeToLive(properties.getOauth().getAccessTokenTtl())
                                .reuseRefreshTokens(false)
                                .build());

                client.getScopes().forEach(builder::scope);

                repository.save(builder.build());
                log.info("Registered OAuth client '{}' with scopes {} ({})",
                        clientId, client.getScopes(), existing != null ? "updated" : "created");
            });
        };
    }
}
