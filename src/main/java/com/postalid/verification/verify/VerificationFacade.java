package com.postalid.verification.verify;

import com.postalid.verification.audit.VerificationContext;
import com.postalid.verification.media.ImageTokenService;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * The single entry point both the REST API and the browser console go through.
 *
 * <p>Verifying and then minting image references is a two-step sequence with one rule that must
 * never be got wrong: references are issued only for a VALID result. Keeping the sequence here,
 * rather than in each controller, means there is one place that rule can be read and one place it
 * could be broken.
 */
@Service
public class VerificationFacade {

    /** A completed verification, with image references present only when the result was VALID. */
    public record Result(VerificationOutcome outcome, Map<String, String> imageReferences) {

        public boolean valid() {
            return outcome.valid();
        }
    }

    private final VerificationService verificationService;
    private final ImageTokenService imageTokenService;

    public VerificationFacade(VerificationService verificationService, ImageTokenService imageTokenService) {
        this.verificationService = verificationService;
        this.imageTokenService = imageTokenService;
    }

    public Result verify(VerificationQuery query, VerificationContext context) {
        VerificationOutcome outcome = verificationService.verify(query, context);

        if (!outcome.valid()) {
            return new Result(outcome, Map.of());
        }

        Map<String, String> references = outcome.matchedCardholder()
                .map(cardholder -> imageTokenService.mintFor(cardholder, context.clientId()))
                .orElse(Map.of());

        return new Result(outcome, references);
    }
}
