package com.postalid.verification.config;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.postalid.verification.web.TokenEndpointRateLimitFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.preauth.AbstractPreAuthenticatedProcessingFilter;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * The token issuer.
 *
 * <p>Only the client-credentials grant is used: callers are machines with a registered id and
 * secret, not people, so there is no browser redirect and no consent step. Tokens are RSA-signed
 * JWTs carrying the granted scopes, short-lived by configuration, and stamped with an audience
 * that the resource server insists on — which stops a token minted for some other service on the
 * same issuer being replayed here.
 */
@Configuration
public class AuthorizationServerConfig {

    /**
     * Runs ahead of the application's own chains and claims only the OAuth2 protocol endpoints.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain authorizationServerFilterChain(HttpSecurity http,
                                                              TokenEndpointRateLimitFilter tokenRateLimitFilter)
            throws Exception {
        OAuth2AuthorizationServerConfigurer configurer = OAuth2AuthorizationServerConfigurer.authorizationServer();

        http
                .securityMatcher(configurer.getEndpointsMatcher())
                .with(configurer, Customizer.withDefaults())
                // Ahead of the configurer's client authentication filter, which the configurer
                // places after AbstractPreAuthenticatedProcessingFilter: a refused request must
                // never reach the BCrypt comparison it exists to protect.
                .addFilterBefore(tokenRateLimitFilter, AbstractPreAuthenticatedProcessingFilter.class)
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                // Protocol endpoints authenticate the client on every request; there is no
                // session or cookie for a CSRF token to protect.
                .csrf(csrf -> csrf.ignoringRequestMatchers(configurer.getEndpointsMatcher()))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));

        return http.build();
    }

    /** Clients live in the database so they can be rotated without a redeploy. */
    @Bean
    public RegisteredClientRepository registeredClientRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcRegisteredClientRepository(jdbcTemplate);
    }

    /**
     * Persisting authorizations keeps memory bounded under a steady stream of token requests and
     * lets a restarted instance still recognise tokens it issued.
     */
    @Bean
    public OAuth2AuthorizationService authorizationService(JdbcTemplate jdbcTemplate,
                                                           RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationService(jdbcTemplate, clients);
    }

    @Bean
    public JWKSource<SecurityContext> jwkSource(PostalIdProperties properties) {
        return JwkSourceFactory.create(properties.getOauth().getJwk());
    }

    /**
     * Validates tokens this application issued.
     *
     * <p>Signature and expiry are checked by default. The audience check is added explicitly:
     * without it, any token this issuer minted for any purpose would be accepted here.
     */
    @Bean
    public JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource, PostalIdProperties properties) {
        JwtDecoder decoder = OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
        if (!(decoder instanceof NimbusJwtDecoder nimbus)) {
            return decoder;
        }

        String audience = properties.getOauth().getAudience();
        String issuer = properties.getOauth().getIssuer();

        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(new JwtTimestampValidator());
        validators.add(new JwtClaimValidator<List<String>>(
                JwtClaimNames.AUD, aud -> aud != null && aud.contains(audience)));
        if (issuer != null && !issuer.isBlank()) {
            validators.add(new JwtIssuerValidator(issuer));
        }

        nimbus.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
        return nimbus;
    }

    @Bean
    public AuthorizationServerSettings authorizationServerSettings(PostalIdProperties properties) {
        AuthorizationServerSettings.Builder builder = AuthorizationServerSettings.builder();
        String issuer = properties.getOauth().getIssuer();
        if (issuer != null && !issuer.isBlank()) {
            builder.issuer(issuer);
        }
        return builder.build();
    }

    /**
     * Stamps the configured audience onto every access token, so the resource server can reject
     * tokens that were not minted for it.
     */
    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> accessTokenCustomizer(PostalIdProperties properties) {
        String audience = properties.getOauth().getAudience();
        return context -> {
            if ("access_token".equals(context.getTokenType().getValue())) {
                context.getClaims().audience(List.of(audience));
            }
        };
    }
}
