package com.postalid.verification.verify;

import com.postalid.verification.audit.VerificationContext;
import com.postalid.verification.domain.Cardholder;
import com.postalid.verification.media.ImageDeliveryService;
import com.postalid.verification.media.ImageTokenService;
import com.postalid.verification.media.WebImage;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * The single entry point both the REST API and the browser console go through.
 *
 * <p>Verifying and then handing out images is a two-step sequence with one rule that must never
 * be got wrong: images, by reference or inline, are issued only for a VALID result. Keeping the
 * sequence here, rather than in each controller, means there is one place that rule can be read
 * and one place it could be broken.
 */
@Service
public class VerificationFacade {

    /** How a VALID result should carry the cardholder's images. */
    public enum ImageDelivery {
        /** Short-lived references, fetched separately. The default. */
        REFERENCES,
        /** The image bytes themselves, embedded in the response; no references are minted. */
        INLINE
    }

    /**
     * A completed verification. On VALID exactly one of the image maps is populated, according to
     * the delivery asked for; on INVALID both are empty.
     */
    public record Result(VerificationOutcome outcome,
                         Map<String, String> imageReferences,
                         Map<String, WebImage> inlineImages) {

        public boolean valid() {
            return outcome.valid();
        }

        /** The matched record's internal key, or empty when nothing matched. */
        public String matchedRecordId() {
            return outcome.matchedCardholder().map(Cardholder::getRecordId).orElse("");
        }
    }

    private final VerificationService verificationService;
    private final ImageTokenService imageTokenService;
    private final ImageDeliveryService imageDeliveryService;

    public VerificationFacade(VerificationService verificationService,
                              ImageTokenService imageTokenService,
                              ImageDeliveryService imageDeliveryService) {
        this.verificationService = verificationService;
        this.imageTokenService = imageTokenService;
        this.imageDeliveryService = imageDeliveryService;
    }

    public Result verify(VerificationQuery query, VerificationContext context) {
        return verify(query, context, ImageDelivery.REFERENCES);
    }

    public Result verify(VerificationQuery query, VerificationContext context, ImageDelivery delivery) {
        VerificationOutcome outcome = verificationService.verify(query, context);

        if (!outcome.valid()) {
            return new Result(outcome, Map.of(), Map.of());
        }

        Cardholder cardholder = outcome.matchedCardholder().orElse(null);
        if (cardholder == null) {
            return new Result(outcome, Map.of(), Map.of());
        }

        if (delivery == ImageDelivery.INLINE) {
            return new Result(outcome, Map.of(), imageDeliveryService.deliverAll(cardholder));
        }
        return new Result(outcome, imageTokenService.mintFor(cardholder, context.imageHolder()), Map.of());
    }
}
