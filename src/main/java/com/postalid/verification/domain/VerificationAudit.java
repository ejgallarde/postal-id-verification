package com.postalid.verification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A record of one verification attempt.
 *
 * <p>This is the only place the reason for an {@code INVALID} is written down. The API response
 * deliberately withholds it, so that the endpoint cannot be walked field by field to reconstruct
 * somebody's details. The PRN itself is stored only as a peppered SHA-256, which is enough to
 * correlate repeated attempts against one card without keeping a second copy of the identifier.
 */
@Entity
@Table(name = "verification_audit")
public class VerificationAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", nullable = false, length = 36)
    private String requestId;

    @Column(name = "client_id", length = 100)
    private String clientId;

    @Column(name = "principal", length = 120)
    private String principal;

    /** {@code API} or {@code WEB}: which surface the attempt came through. */
    @Column(name = "channel", nullable = false, length = 16)
    private String channel;

    @Column(name = "result", nullable = false, length = 16)
    private String result;

    @Column(name = "failure_reason_code", length = 32)
    private String failureReasonCode;

    /**
     * Every field that failed, comma-separated, using the caller's own field names.
     * Null unless the failure was a field mismatch.
     */
    @Column(name = "mismatched_fields", length = 255)
    private String mismatchedFields;

    @Column(name = "prn_hash", nullable = false, length = 64)
    private String prnHash;

    @Column(name = "matched_record_id", length = 32)
    private String matchedRecordId;

    @Column(name = "remote_ip", length = 45)
    private String remoteIp;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public Long getId() { return id; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }

    public String getPrincipal() { return principal; }
    public void setPrincipal(String principal) { this.principal = principal; }

    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }

    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }

    public String getFailureReasonCode() { return failureReasonCode; }
    public void setFailureReasonCode(String failureReasonCode) { this.failureReasonCode = failureReasonCode; }

    public String getMismatchedFields() { return mismatchedFields; }
    public void setMismatchedFields(String mismatchedFields) { this.mismatchedFields = mismatchedFields; }

    public String getPrnHash() { return prnHash; }
    public void setPrnHash(String prnHash) { this.prnHash = prnHash; }

    public String getMatchedRecordId() { return matchedRecordId; }
    public void setMatchedRecordId(String matchedRecordId) { this.matchedRecordId = matchedRecordId; }

    public String getRemoteIp() { return remoteIp; }
    public void setRemoteIp(String remoteIp) { this.remoteIp = remoteIp; }

    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
