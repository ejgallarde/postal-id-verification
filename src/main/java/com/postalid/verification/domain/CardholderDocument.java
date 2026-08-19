package com.postalid.verification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A single file captured with an enrollment record: the photo, the signature, a fingerprint,
 * or a supporting document scan.
 *
 * <p>Where the bytes live depends on {@link StorageKind}. A {@code MANAGED} document was copied
 * into the blob store and can be served; an {@code EXTERNAL} one is recorded with its metadata,
 * digest and a pointer, and its bytes stay in the source archive. Either way the SHA-256 is held
 * here, so integrity can be checked without pulling megabytes through the database.
 */
@Entity
@Table(name = "cardholder_document")
public class CardholderDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cardholder_id", nullable = false)
    private Cardholder cardholder;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "doc_code", nullable = false)
    private DocumentType documentType;

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @Enumerated(EnumType.STRING)
    @Column(name = "storage_kind", nullable = false, length = 16)
    private StorageKind storageKind;

    /**
     * Path relative to the configured storage root. Never absolute, never caller-supplied.
     * Null for {@link StorageKind#EXTERNAL} documents, whose bytes were never copied.
     */
    @Column(name = "storage_path", length = 512)
    private String storagePath;

    /**
     * Where the file sat at ingest, relative to the ingest source root.
     *
     * <p>Relative rather than absolute so that an archive which is later moved or remounted stays
     * resolvable: the current archive root plus this path.
     */
    @Column(name = "source_path", length = 1024)
    private String sourcePath;

    @Column(name = "content_type", nullable = false, length = 64)
    private String contentType;

    @Column(name = "byte_size", nullable = false)
    private long byteSize;

    @Column(name = "sha256", nullable = false, length = 64)
    private String sha256;

    @Column(name = "captured_at")
    private Instant capturedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }

    public Cardholder getCardholder() { return cardholder; }
    public void setCardholder(Cardholder cardholder) { this.cardholder = cardholder; }

    public DocumentType getDocumentType() { return documentType; }
    public void setDocumentType(DocumentType documentType) { this.documentType = documentType; }

    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename; }

    public StorageKind getStorageKind() { return storageKind; }
    public void setStorageKind(StorageKind storageKind) { this.storageKind = storageKind; }

    public String getStoragePath() { return storagePath; }
    public void setStoragePath(String storagePath) { this.storagePath = storagePath; }

    public String getSourcePath() { return sourcePath; }
    public void setSourcePath(String sourcePath) { this.sourcePath = sourcePath; }

    /** True when this document's bytes are held locally and can be served. */
    public boolean isServable() {
        return storageKind == StorageKind.MANAGED && storagePath != null;
    }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public long getByteSize() { return byteSize; }
    public void setByteSize(long byteSize) { this.byteSize = byteSize; }

    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }

    public Instant getCapturedAt() { return capturedAt; }
    public void setCapturedAt(Instant capturedAt) { this.capturedAt = capturedAt; }
}
