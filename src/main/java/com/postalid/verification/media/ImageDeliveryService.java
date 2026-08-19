package com.postalid.verification.media;

import com.postalid.verification.domain.CardholderDocument;
import com.postalid.verification.storage.BlobStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Optional;

/**
 * Turns an image reference into displayable bytes.
 *
 * <p>The REST API and the browser console reach this by different routes — one authenticated by
 * bearer token, the other by session — but both must apply the same rules about what may be
 * served. Keeping redemption, the retrievability check and transcoding together means neither
 * caller can accidentally skip a step.
 *
 * <p>Every failure returns empty. Callers turn that into an undifferentiated 404, so a reference
 * that expired cannot be told apart from one that never existed.
 */
@Service
public class ImageDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(ImageDeliveryService.class);

    private final ImageTokenService tokenService;
    private final ImageTranscoder transcoder;
    private final BlobStore blobStore;

    public ImageDeliveryService(ImageTokenService tokenService,
                                ImageTranscoder transcoder,
                                BlobStore blobStore) {
        this.tokenService = tokenService;
        this.transcoder = transcoder;
        this.blobStore = blobStore;
    }

    public Optional<WebImage> deliver(String reference) {
        Optional<CardholderDocument> redeemed = tokenService.redeem(reference);
        if (redeemed.isEmpty()) {
            return Optional.empty();
        }

        CardholderDocument document = redeemed.get();

        // Minting already refuses non-retrievable types. Re-checking here is cheap, and this is
        // the line between serving a portrait and serving a fingerprint.
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
