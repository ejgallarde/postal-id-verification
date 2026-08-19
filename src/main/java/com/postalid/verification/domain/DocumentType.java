package com.postalid.verification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The kinds of file an enrollment packet can carry, keyed by the suffix used in packet filenames.
 *
 * <p>{@link #isPubliclyRetrievable()} is the single switch that decides whether the API will ever
 * stream bytes of this kind. Only {@code Photo} and {@code Signature} are true; fingerprints and
 * supporting document scans are stored and mapped but unreachable from any route.
 */
@Entity
@Table(name = "document_type")
public class DocumentType {

    /** Codes the media layer is allowed to serve. Mirrors the seeded flag; see V2 migration. */
    public static final String PHOTO = "Photo";
    public static final String SIGNATURE = "Signature";

    @Id
    @Column(name = "code", length = 16)
    private String code;

    @Column(name = "description", nullable = false, length = 120)
    private String description;

    @Column(name = "category", nullable = false, length = 24)
    private String category;

    @Column(name = "is_publicly_retrievable", nullable = false)
    private boolean publiclyRetrievable;

    protected DocumentType() {
    }

    public String getCode() { return code; }

    public String getDescription() { return description; }

    public String getCategory() { return category; }

    public boolean isPubliclyRetrievable() { return publiclyRetrievable; }
}
