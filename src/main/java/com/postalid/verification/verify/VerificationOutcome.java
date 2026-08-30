package com.postalid.verification.verify;

import com.postalid.verification.domain.Cardholder;

import java.util.List;
import java.util.Optional;

/**
 * The internal result of a verification, carrying more than most callers are allowed to see.
 *
 * <p>{@link #failureReasons()} lists every reason the check failed, not just the first, so both
 * the audit trail and a diagnostics-scoped response can report the complete picture. Only
 * {@link #valid()} influences whether a caller without that scope sees anything beyond a verdict.
 */
public record VerificationOutcome(boolean valid,
                                  List<FailureReason> failureReasons,
                                  Cardholder cardholder,
                                  CardValidityWindow validity) {

    public static VerificationOutcome valid(Cardholder cardholder, CardValidityWindow validity) {
        return new VerificationOutcome(true, List.of(), cardholder, validity);
    }

    public static VerificationOutcome invalid(List<FailureReason> reasons, Cardholder cardholder) {
        return new VerificationOutcome(false, List.copyOf(reasons), cardholder, null);
    }

    public static VerificationOutcome invalid(FailureReason reason, Cardholder cardholder) {
        return invalid(List.of(reason), cardholder);
    }

    public static VerificationOutcome invalid(FailureReason reason) {
        return invalid(List.of(reason), null);
    }

    public Optional<Cardholder> matchedCardholder() {
        return Optional.ofNullable(cardholder);
    }

    public Optional<CardValidityWindow> validityWindow() {
        return Optional.ofNullable(validity);
    }

    /** The reason recorded as primary in the audit trail. */
    public FailureReason primaryFailureReason() {
        return failureReasons.isEmpty() ? null : failureReasons.get(0);
    }
}
