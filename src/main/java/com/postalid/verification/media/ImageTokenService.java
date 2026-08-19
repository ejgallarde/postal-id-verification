package com.postalid.verification.media;

import com.postalid.verification.config.PostalIdProperties;
import com.postalid.verification.domain.Cardholder;
import com.postalid.verification.domain.CardholderDocument;
import com.postalid.verification.domain.CardholderDocumentRepository;
import com.postalid.verification.domain.ImageAccessToken;
import com.postalid.verification.domain.ImageAccessTokenRepository;
import com.postalid.verification.storage.BlobStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Mints and redeems the short-lived references that let a caller fetch a verified cardholder's
 * photo and signature.
 *
 * <p>A reference is 256 bits of {@link SecureRandom}, handed out once on a VALID verification and
 * stored only as a SHA-256. Storing the digest rather than the value means a leaked database
 * export does not contain working URLs. Expiry and a use counter stop a reference from becoming a
 * permanent handle on someone's photograph, while allowing the handful of repeats a browser
 * generates by reloading a page.
 *
 * <p>Minting is restricted to document types flagged publicly retrievable, which is only the
 * photo and the signature. Fingerprints and certificate scans can never be referenced.
 */
@Service
public class ImageTokenService {

    private static final Logger log = LoggerFactory.getLogger(ImageTokenService.class);
    private static final int REFERENCE_BYTES = 32;

    private final ImageAccessTokenRepository tokenRepository;
    private final CardholderDocumentRepository documentRepository;
    private final PostalIdProperties.Media config;
    private final SecureRandom random = new SecureRandom();

    public ImageTokenService(ImageAccessTokenRepository tokenRepository,
                             CardholderDocumentRepository documentRepository,
                             PostalIdProperties properties) {
        this.tokenRepository = tokenRepository;
        this.documentRepository = documentRepository;
        this.config = properties.getMedia();
    }

    /**
     * Issues one reference per retrievable image the cardholder actually has.
     *
     * @return document code to opaque reference; empty when nothing retrievable was captured
     */
    @Transactional
    public Map<String, String> mintFor(Cardholder cardholder, String clientId) {
        List<CardholderDocument> retrievable =
                documentRepository.findByCardholderIdAndDocumentTypePubliclyRetrievableTrue(cardholder.getId());

        Instant now = Instant.now();
        Map<String, String> references = new LinkedHashMap<>();

        for (CardholderDocument document : retrievable) {
            String reference = newReference();

            ImageAccessToken token = new ImageAccessToken();
            token.setTokenHash(BlobStore.sha256(reference));
            token.setCardholderId(cardholder.getId());
            token.setDocCode(document.getDocumentType().getCode());
            token.setClientId(clientId);
            token.setIssuedAt(now);
            token.setExpiresAt(now.plus(config.getTokenTtl()));
            token.setUsesRemaining(config.getTokenMaxUses());
            token.setRevoked(false);

            tokenRepository.save(token);
            references.put(document.getDocumentType().getCode(), reference);
        }
        return references;
    }

    /**
     * Spends one use of a reference and returns the document it points at.
     *
     * <p>Unknown, expired, exhausted and revoked references are all indistinguishable to the
     * caller: every one of them comes back empty.
     */
    @Transactional
    public Optional<CardholderDocument> redeem(String reference) {
        if (reference == null || reference.isBlank()) {
            return Optional.empty();
        }

        Optional<ImageAccessToken> found = tokenRepository.findByTokenHash(BlobStore.sha256(reference));
        if (found.isEmpty()) {
            return Optional.empty();
        }

        ImageAccessToken token = found.get();
        if (!token.isUsable(Instant.now())) {
            return Optional.empty();
        }

        token.consumeUse();
        tokenRepository.save(token);

        return documentRepository.findByCardholderIdAndDocumentTypeCode(
                token.getCardholderId(), token.getDocCode());
    }

    private String newReference() {
        byte[] bytes = new byte[REFERENCE_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Expired rows carry no secret, but there is no reason to keep accumulating them. */
    @Scheduled(fixedDelayString = "PT15M")
    @Transactional
    public void purgeExpired() {
        int removed = tokenRepository.deleteExpiredBefore(Instant.now().minusSeconds(3600));
        if (removed > 0) {
            log.debug("Purged {} expired image access tokens", removed);
        }
    }
}
