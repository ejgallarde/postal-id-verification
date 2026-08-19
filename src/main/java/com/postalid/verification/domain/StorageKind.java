package com.postalid.verification.domain;

/**
 * Whether a captured file's bytes were copied into the blob store, or left where they were.
 *
 * <p>The split follows {@link DocumentType#isPubliclyRetrievable()} exactly, and not by accident:
 * the only reason to hold a copy is to be able to serve it. A photograph and signature together
 * are a fraction of a percent of an enrollment packet; the certificates, receipts, forms and
 * fingerprints are effectively all of it, and no route can return them.
 */
public enum StorageKind {

    /** Bytes copied into the blob store. Servable. */
    MANAGED,

    /**
     * Metadata and digest recorded; bytes left in the source archive.
     *
     * <p>Consequence worth knowing: if a document type is ever made publicly retrievable, rows
     * already ingested as EXTERNAL have no bytes to serve. Re-ingest those packets rather than
     * flipping the flag alone.
     */
    EXTERNAL
}
