package com.postalid.verification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A short-lived capability to fetch one image of one cardholder.
 *
 * <p>Only the SHA-256 of the reference is kept, so read access to this table does not hand
 * anybody a working URL. Expiry plus a use counter keep the reference from turning into a
 * durable handle on someone's photograph.
 */
@Entity
@Table(name = "image_access_token")
public class ImageAccessToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "cardholder_id", nullable = false)
    private Long cardholderId;

    @Column(name = "doc_code", nullable = false, length = 16)
    private String docCode;

    @Column(name = "client_id", length = 100)
    private String clientId;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "uses_remaining", nullable = false)
    private int usesRemaining;

    @Column(name = "revoked", nullable = false)
    private boolean revoked;

    public Long getId() { return id; }

    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }

    public Long getCardholderId() { return cardholderId; }
    public void setCardholderId(Long cardholderId) { this.cardholderId = cardholderId; }

    public String getDocCode() { return docCode; }
    public void setDocCode(String docCode) { this.docCode = docCode; }

    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }

    public Instant getIssuedAt() { return issuedAt; }
    public void setIssuedAt(Instant issuedAt) { this.issuedAt = issuedAt; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public int getUsesRemaining() { return usesRemaining; }
    public void setUsesRemaining(int usesRemaining) { this.usesRemaining = usesRemaining; }

    public boolean isRevoked() { return revoked; }
    public void setRevoked(boolean revoked) { this.revoked = revoked; }
}
