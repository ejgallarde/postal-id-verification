package com.postalid.verification.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;

import java.io.InputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;

/**
 * Supplies the RSA key pair that signs access tokens.
 *
 * <p>A keystore is used when one is configured. Otherwise a key pair is generated in memory,
 * which keeps a fresh checkout runnable but means tokens die with the process and cannot be
 * validated by a second instance. That trade-off is logged loudly rather than left to be
 * discovered in production.
 */
final class JwkSourceFactory {

    private static final Logger log = LoggerFactory.getLogger(JwkSourceFactory.class);
    private static final int RSA_KEY_SIZE = 2048;

    private JwkSourceFactory() {
    }

    static JWKSource<SecurityContext> create(PostalIdProperties.Oauth.Jwk config) {
        RSAKey rsaKey = config.getKeystoreLocation() == null || config.getKeystoreLocation().isBlank()
                ? generateEphemeral()
                : loadFromKeystore(config);
        return new ImmutableJWKSet<>(new JWKSet(rsaKey));
    }

    private static RSAKey loadFromKeystore(PostalIdProperties.Oauth.Jwk config) {
        Resource resource = new DefaultResourceLoader().getResource(config.getKeystoreLocation());
        char[] storePassword = toChars(config.getKeystorePassword());
        char[] keyPassword = toChars(config.getKeyPassword() != null
                ? config.getKeyPassword()
                : config.getKeystorePassword());

        try (InputStream in = resource.getInputStream()) {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(in, storePassword);

            Certificate certificate = keyStore.getCertificate(config.getKeyAlias());
            if (certificate == null) {
                throw new IllegalStateException(
                        "No certificate under alias '" + config.getKeyAlias() + "' in the JWT keystore");
            }
            PrivateKey privateKey = (PrivateKey) keyStore.getKey(config.getKeyAlias(), keyPassword);
            if (privateKey == null) {
                throw new IllegalStateException(
                        "No private key under alias '" + config.getKeyAlias() + "' in the JWT keystore");
            }

            log.info("Loaded JWT signing key '{}' from keystore", config.getKeyAlias());
            return new RSAKey.Builder((RSAPublicKey) certificate.getPublicKey())
                    .privateKey((RSAPrivateKey) privateKey)
                    .keyID(config.getKeyAlias())
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to load the JWT signing key from " + config.getKeystoreLocation(), e);
        }
    }

    private static RSAKey generateEphemeral() {
        log.warn("No JWT keystore configured; generating an ephemeral RSA key. "
                + "Every issued token becomes invalid on restart and multiple instances cannot "
                + "validate each other's tokens. Set postalid.oauth.jwk.keystore-location before deploying.");
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(RSA_KEY_SIZE);
            KeyPair keyPair = generator.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                    .privateKey((RSAPrivateKey) keyPair.getPrivate())
                    .keyID(UUID.randomUUID().toString())
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to generate an RSA key pair", e);
        }
    }

    private static char[] toChars(String value) {
        return value == null ? new char[0] : value.toCharArray();
    }
}
