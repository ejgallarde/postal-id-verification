package com.postalid.verification.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

public interface VerificationAuditRepository extends JpaRepository<VerificationAudit, Long> {

    /** Backs the progressive throttle: how hard has this client been failing lately? */
    long countByClientIdAndResultAndCreatedAtAfter(String clientId, String result, Instant since);
}
