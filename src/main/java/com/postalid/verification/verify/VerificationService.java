package com.postalid.verification.verify;

import com.postalid.verification.audit.AuditService;
import com.postalid.verification.audit.VerificationContext;
import com.postalid.verification.config.PostalIdProperties;
import com.postalid.verification.domain.Cardholder;
import com.postalid.verification.domain.CardholderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Answers the only question this system exists to answer: do these details belong to a real,
 * issued Postal ID?
 *
 * <p>Two deliberate behaviours shape this class, both aimed at the fact that a permissive
 * verification endpoint is an oracle over other people's personal data:
 *
 * <ol>
 *   <li>Every unsuccessful path produces the same response for an ordinary caller. Whether the
 *       reference number is unknown, the card was never issued, or three of five fields were
 *       wrong, they see an unadorned INVALID. The detail goes to the audit trail, and to
 *       diagnostics-scoped clients only.</li>
 *   <li>An unknown reference number is still compared against a decoy record, so the in-memory
 *       comparison work is the same on every refusal. This is hygiene rather than a guarantee:
 *       the comparison costs microseconds, while the indexed lookup that precedes it, and the
 *       row it may or may not load, cost milliseconds and vary with the database. Over a network
 *       that difference is buried in jitter, but a caller averaging many samples from close by
 *       could still separate the two populations. The rate limits and the enumeration throttle
 *       are what make that sampling expensive; if a stronger guarantee is ever needed, pad every
 *       INVALID response to a fixed minimum latency.</li>
 * </ol>
 */
@Service
public class VerificationService {

    /**
     * Stand-in compared against when no record is found, so the work done is comparable to a
     * genuine mismatch. Values are arbitrary and match nothing a caller could submit.
     */
    private static final Cardholder DECOY = buildDecoy();

    private final CardholderRepository cardholderRepository;
    private final MatchRules matchRules;
    private final FieldNormalizer normalizer;
    private final AuditService auditService;
    private final CardValidityResolver validityResolver;
    private final PostalIdProperties.Verify config;

    public VerificationService(CardholderRepository cardholderRepository,
                               MatchRules matchRules,
                               FieldNormalizer normalizer,
                               AuditService auditService,
                               CardValidityResolver validityResolver,
                               PostalIdProperties properties) {
        this.cardholderRepository = cardholderRepository;
        this.matchRules = matchRules;
        this.normalizer = normalizer;
        this.auditService = auditService;
        this.validityResolver = validityResolver;
        this.config = properties.getVerify();
    }

    @Transactional(readOnly = true)
    public VerificationOutcome verify(VerificationQuery query, VerificationContext context) {
        String normalizedPrn = normalizer.normalizeToEmpty(query.postalRefNo());

        VerificationOutcome outcome = evaluate(query, normalizedPrn);
        auditService.recordAttempt(context, normalizedPrn, outcome);
        return outcome;
    }

    private VerificationOutcome evaluate(VerificationQuery query, String normalizedPrn) {
        Optional<Cardholder> found = cardholderRepository.findByPostalRefNo(normalizedPrn);

        if (found.isEmpty()) {
            // Burn the same comparison work a real mismatch would, then report nothing useful.
            matchRules.evaluate(query, DECOY);
            return VerificationOutcome.invalid(FailureReason.NO_SUCH_RECORD);
        }

        Cardholder record = found.get();

        FailureReason ineligible = matchRules.evaluateEligibility(record);
        if (ineligible != null) {
            // Still compare, so an un-issued record cannot be distinguished by response time.
            matchRules.evaluate(query, record);
            return VerificationOutcome.invalid(ineligible, record);
        }

        List<FailureReason> mismatches = matchRules.evaluate(query, record);
        if (!mismatches.isEmpty()) {
            return VerificationOutcome.invalid(mismatches, record);
        }

        CardValidityWindow validity = validityResolver.resolve(record);

        // Expiry is reported, not enforced, unless a deployment opts in. An expired card belongs
        // to a real person whose identity just checked out; collapsing that into the same INVALID
        // as a forgery would throw away the distinction the caller most needs.
        if (validity.expired() && config.isTreatExpiredAsInvalid()) {
            return VerificationOutcome.invalid(FailureReason.CARD_EXPIRED, record);
        }

        return VerificationOutcome.valid(record, validity);
    }

    private static Cardholder buildDecoy() {
        Cardholder decoy = new Cardholder();
        decoy.setRecordId("00000000000000000000");
        decoy.setPostalRefNo("XXXXXXXXXXXX");
        decoy.setFirstName("-");
        decoy.setLastName("-");
        decoy.setFirstNameNorm(" UNMATCHABLE-FIRST");
        decoy.setMiddleNameNorm(" UNMATCHABLE-MIDDLE");
        decoy.setLastNameNorm(" UNMATCHABLE-LAST");
        decoy.setDateOfBirth(LocalDate.of(1, 1, 1));
        decoy.setDemoStatus("XXX");
        return decoy;
    }
}
