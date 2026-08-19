package com.postalid.verification.verify;

import com.postalid.verification.domain.Cardholder;

import java.util.Optional;

/**
 * The internal result of a verification, carrying more than the caller is allowed to see.
 *
 * <p>{@link #failureReason()} and {@link #cardholder()} feed the audit trail and the image-token
 * minting respectively. Only {@link #valid()} influences the response body.
 */
public record VerificationOutcome(boolean valid, FailureReason failureReason, Cardholder cardholder) {

    public static VerificationOutcome valid(Cardholder cardholder) {
        return new VerificationOutcome(true, null, cardholder);
    }

    /** An invalid outcome where a record was found, so the audit can name it. */
    public static VerificationOutcome invalid(FailureReason reason, Cardholder cardholder) {
        return new VerificationOutcome(false, reason, cardholder);
    }

    public static VerificationOutcome invalid(FailureReason reason) {
        return new VerificationOutcome(false, reason, null);
    }

    public Optional<Cardholder> matchedCardholder() {
        return Optional.ofNullable(cardholder);
    }
}
