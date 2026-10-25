package com.postalid.verification.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface VerificationAuditRepository extends JpaRepository<VerificationAudit, Long> {
}
