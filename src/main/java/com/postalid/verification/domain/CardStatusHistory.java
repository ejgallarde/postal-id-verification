package com.postalid.verification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** One step of the enrollment status trail, from {@code *__demostatusdtl.csv}. */
@Entity
@Table(name = "card_status_history")
public class CardStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cardholder_id", nullable = false)
    private Cardholder cardholder;

    /** The {@code ID} column of the source CSV, kept for traceability back to the packet. */
    @Column(name = "source_id")
    private Long sourceId;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /**
     * Wall-clock time as written by the capture station. Held as a {@code LocalDateTime} because
     * the source carries no offset: reading a zone into it would mean inventing one, and the
     * value would then shift with the server's timezone.
     */
    @Column(name = "status_timestamp")
    private LocalDateTime statusTimestamp;

    @Column(name = "processed_by", length = 64)
    private String processedBy;

    public Long getId() { return id; }

    public Cardholder getCardholder() { return cardholder; }
    public void setCardholder(Cardholder cardholder) { this.cardholder = cardholder; }

    public Long getSourceId() { return sourceId; }
    public void setSourceId(Long sourceId) { this.sourceId = sourceId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public LocalDateTime getStatusTimestamp() { return statusTimestamp; }
    public void setStatusTimestamp(LocalDateTime statusTimestamp) { this.statusTimestamp = statusTimestamp; }

    public String getProcessedBy() { return processedBy; }
    public void setProcessedBy(String processedBy) { this.processedBy = processedBy; }
}
