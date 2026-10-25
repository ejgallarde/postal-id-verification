package com.postalid.verification.media;

import com.postalid.verification.domain.Cardholder;
import com.postalid.verification.domain.CardholderDocument;
import com.postalid.verification.domain.CardholderDocumentRepository;
import com.postalid.verification.storage.BlobStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Turns an image reference, or a verified cardholder, into displayable bytes.
 *
 * <p>The REST API and the browser console reach this by different routes — one authenticated by
 * bearer token, the other by session — and the API can also ask for images inline in the
 * verification response. All three must apply the same rules about what may be served. Keeping
 * the retrievability check and transcoding here, in {@link #load}, means no route can
 * accidentally skip a step.
 *
 * <p>Every failure returns empty. Callers turn that into an undifferentiated 404, so a reference
 * that expired cannot be told apart from one that never existed.
 */
@Service
public class ImageDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(ImageDeliveryService.class);

    private final ImageTokenService tokenService;
    private final CardholderDocumentRepository documentRepository;
    private final ImageTranscoder transcoder;
    private final BlobStore blobStore;

    public ImageDeliveryService(ImageTokenService tokenService,
                                CardholderDocumentRepository documentRepository,
                                ImageTranscoder transcoder,
                                BlobStore blobStore) {
        this.tokenService = tokenService;
        this.documentRepository = documentRepository;
        this.transcoder = transcoder;
        this.blobStore = blobStore;
    }

    /**
     * @param holder the caller asking, as {@link com.postalid.verification.audit.VerificationContext#imageHolder}
     *               builds it; only the holder a reference was minted for can redeem it
     */
    public Optional<WebImage> deliver(String reference, String holder) {
        return tokenService.redeem(reference, holder).flatMap(this::load);
    }

    /**
     * Every servable image of a cardholder, for embedding straight into a VALID response.
     *
     * <p>Only {@link com.postalid.verification.verify.VerificationFacade} calls this, and only
     * after a VALID result — the same rule that governs minting references.
     *
     * @return document code to image; an image that fails to load is left out rather than failing
     *         the whole verification
     */
    public Map<String, WebImage> deliverAll(Cardholder cardholder) {
        Map<String, WebImage> images = new LinkedHashMap<>();
        for (CardholderDocument document :
                documentRepository.findByCardholderIdAndDocumentTypePubliclyRetrievableTrue(cardholder.getId())) {
            load(document).ifPresent(image -> images.put(document.getDocumentType().getCode(), image));
        }
        return images;
    }

    private Optional<WebImage> load(CardholderDocument document) {
        // Minting and the inline query already refuse non-retrievable types. Re-checking here is
        // cheap, and this is the line between serving a portrait and serving a fingerprint.
        if (!document.getDocumentType().isPubliclyRetrievable()) {
            log.warn("Refused to serve document type '{}', which is not publicly retrievable",
                    document.getDocumentType().getCode());
            return Optional.empty();
        }

        // Only MANAGED documents have local bytes. Reaching here with an EXTERNAL one means a
        // document type was made retrievable after those rows were ingested; the packets need
        // re-ingesting, and in the meantime this must not become a stack trace on a 500.
        if (!document.isServable()) {
            log.warn("Document {} of type '{}' is retrievable but was stored as {}; re-ingest the packet",
                    document.getId(), document.getDocumentType().getCode(), document.getStorageKind());
            return Optional.empty();
        }

        try {
            byte[] stored = blobStore.read(document.getStoragePath());
            return Optional.of(transcoder.toWebSafe(stored, document.getContentType()));
        } catch (IOException | IllegalArgumentException e) {
            // Identify the document by id only: never the storage path or the person it belongs to.
            log.error("Failed to load document {}: {}", document.getId(), e.getMessage());
            return Optional.empty();
        }
    }
}
