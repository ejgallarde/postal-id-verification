package com.postalid.verification.audit;

import com.postalid.verification.domain.Cardholder;
import com.postalid.verification.domain.VerificationAudit;
import com.postalid.verification.domain.VerificationAuditRepository;
import com.postalid.verification.verify.FailureReason;
import com.postalid.verification.verify.VerificationOutcome;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.stream.Collectors;

/**
 * Writes the verification audit trail.
 *
 * <p>Runs in its own transaction so that an audit row survives even if the surrounding request
 * later fails: an attempt that happened but was not recorded is worse than a duplicate record.
 */
@Service
public class AuditService {

    private static final int USER_AGENT_MAX = 255;

    private final VerificationAuditRepository repository;
    private final PrnHasher prnHasher;

    public AuditService(VerificationAuditRepository repository, PrnHasher prnHasher) {
        this.repository = repository;
        this.prnHasher = prnHasher;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAttempt(VerificationContext context, String normalizedPrn, VerificationOutcome outcome) {
        VerificationAudit audit = new VerificationAudit();
        audit.setRequestId(context.requestId());
        audit.setClientId(context.clientId());
        audit.setPrincipal(context.principal());
        audit.setChannel(context.channel().name());
        audit.setResult(outcome.valid() ? "VALID" : "INVALID");

        FailureReason primary = outcome.primaryFailureReason();
        audit.setFailureReasonCode(primary == null ? null : primary.name());

        // The complete list, so an operator investigating a run of refusals sees every field that
        // failed rather than just the first one the rules happened to check.
        String mismatched = outcome.failureReasons().stream()
                .filter(FailureReason::isFieldMismatch)
                .map(FailureReason::requestField)
                .collect(Collectors.joining(","));
        audit.setMismatchedFields(mismatched.isEmpty() ? null : mismatched);

        audit.setPrnHash(prnHasher.hash(normalizedPrn));
        audit.setMatchedRecordId(outcome.matchedCardholder().map(Cardholder::getRecordId).orElse(null));
        audit.setRemoteIp(context.remoteIp());
        audit.setUserAgent(truncate(context.userAgent()));
        audit.setCreatedAt(Instant.now());

        repository.save(audit);
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= USER_AGENT_MAX ? value : value.substring(0, USER_AGENT_MAX);
    }
}
