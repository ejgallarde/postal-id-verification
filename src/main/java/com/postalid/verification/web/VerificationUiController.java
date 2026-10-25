package com.postalid.verification.web;

import com.postalid.verification.api.CardResponseMapper;
import com.postalid.verification.api.dto.VerifyRequest;
import com.postalid.verification.audit.VerificationContext;
import com.postalid.verification.config.PostalIdProperties;
import com.postalid.verification.domain.DocumentType;
import com.postalid.verification.media.ImageDeliveryService;
import com.postalid.verification.media.WebImage;
import com.postalid.verification.verify.VerificationFacade;
import com.postalid.verification.verify.VerificationQuery;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.ui.Model;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * The operator console.
 *
 * <p>Server-rendered on purpose. Verification details go straight from the form to the service
 * without a browser-held API token, and the result page shows only what the card itself prints
 * plus two opaque image references — so a screenshot or a shoulder-surfer picks up nothing the
 * operator was not already holding in their hand.
 *
 * <p>Images are served here rather than through {@code /api/v1/images} because that route
 * requires a bearer token a session-authenticated browser does not have. Both routes redeem the
 * same references through the same service, so the rules do not diverge.
 */
@Controller
public class VerificationUiController {

    /**
     * Namespaces operators apart from OAuth clients in the enumeration throttle, so an operator
     * whose username happens to equal a client id cannot trip, or reset, that client's count.
     */
    private static final String OPERATOR_KEY_PREFIX = "operator:";

    /** Shown on the comparison view's provenance band. */
    private static final DateTimeFormatter VERIFIED_AT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private final VerificationFacade verificationFacade;
    private final ImageDeliveryService imageDeliveryService;
    private final CardResponseMapper mapper;
    private final QrCodeRenderer qrCodeRenderer;
    private final RateLimiter rateLimiter;
    private final EnumerationThrottle enumerationThrottle;
    private final PostalIdProperties properties;

    public VerificationUiController(VerificationFacade verificationFacade,
                                    ImageDeliveryService imageDeliveryService,
                                    CardResponseMapper mapper,
                                    QrCodeRenderer qrCodeRenderer,
                                    RateLimiter rateLimiter,
                                    EnumerationThrottle enumerationThrottle,
                                    PostalIdProperties properties) {
        this.verificationFacade = verificationFacade;
        this.imageDeliveryService = imageDeliveryService;
        this.mapper = mapper;
        this.qrCodeRenderer = qrCodeRenderer;
        this.rateLimiter = rateLimiter;
        this.enumerationThrottle = enumerationThrottle;
        this.properties = properties;
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @GetMapping("/")
    public String form(Model model) {
        model.addAttribute("form", VerifyForm.empty());
        return "verify";
    }

    @PostMapping("/verify")
    public String submit(@Valid @ModelAttribute("form") VerifyForm form,
                         BindingResult binding,
                         HttpServletRequest request,
                         HttpServletResponse response,
                         Model model) {

        if (binding.hasErrors()) {
            return "verify";
        }

        // The same two controls the API applies per client, applied per operator. A session is a
        // credential like any other, and without these a script driving a logged-in browser
        // would face only the per-address budget.
        String operator = RequestContextSupport.currentPrincipalName();
        String throttleKey = OPERATOR_KEY_PREFIX + operator;

        RateLimiter.Decision decision = rateLimiter.checkVerifyByOperator(operator);
        long retryAfter = decision.allowed() ? enumerationThrottle.retryAfterSeconds(throttleKey)
                                             : decision.retryAfterSeconds();
        if (retryAfter > 0) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));
            model.addAttribute("retryAfterSeconds", retryAfter);
            return "verify";
        }

        String requestId = UUID.randomUUID().toString();
        VerificationContext context = new VerificationContext(
                requestId,
                operator,
                operator,
                VerificationContext.Channel.WEB,
                RequestContextSupport.remoteIp(request),
                RequestContextSupport.userAgent(request));

        VerificationFacade.Result result = verificationFacade.verify(
                new VerificationQuery(
                        form.getPostalRefNo(),
                        form.getFirstName(),
                        form.getMiddleName(),
                        form.getLastName(),
                        form.getDateOfBirth()),
                context);

        if (result.valid()) {
            enumerationThrottle.recordSuccess(throttleKey, result.matchedRecordId());
        } else {
            enumerationThrottle.recordFailure(throttleKey);
        }

        Map<String, String> references = result.imageReferences();
        model.addAttribute("valid", result.valid());
        model.addAttribute("requestId", requestId);
        model.addAttribute("photoRef", references.get(DocumentType.PHOTO));
        model.addAttribute("signatureRef", references.get(DocumentType.SIGNATURE));
        model.addAttribute("expiresInSeconds", properties.getMedia().getTokenTtl().toSeconds());

        // The operator is holding the card, so the record is laid out in the same field positions
        // the card uses. Same field selection as the API: only what the card itself prints.
        if (result.valid()) {
            String verifiedAt = VERIFIED_AT.format(Instant.now());

            model.addAttribute("cardholder",
                    result.outcome().matchedCardholder().map(mapper::toDetails).orElse(null));
            model.addAttribute("validity", mapper.toValidity(result.outcome().validity()));
            model.addAttribute("verifiedAt", verifiedAt);

            // Encodes the verification, not the cardholder: it resolves to the audit row and
            // carries no personal data, so a screenshot leaks nothing machine-readable.
            model.addAttribute("qrSvg", qrCodeRenderer.toSvg(requestId, verifiedAt));
        }

        return "result";
    }

    /** Serves an image to the logged-in operator's browser using the same reference the API uses. */
    @GetMapping("/ui/images/{reference}")
    public ResponseEntity<byte[]> image(@PathVariable String reference) {
        String holder = VerificationContext.imageHolder(
                VerificationContext.Channel.WEB, RequestContextSupport.currentPrincipalName());
        WebImage image = imageDeliveryService.deliver(reference, holder)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Image reference is not valid"));

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .header("X-Content-Type-Options", "nosniff")
                .body(image.data());
    }

    /**
     * Form backing object.
     *
     * <p>Mirrors {@link VerifyRequest} rather than reusing it: a record with constructor-level
     * validation does not bind well to an HTML form that must round-trip partially-filled,
     * invalid input back to the user for correction.
     */
    public static class VerifyForm {

        @jakarta.validation.constraints.NotBlank(message = "ID number is required")
        @jakarta.validation.constraints.Pattern(regexp = "^[A-Za-z0-9]{8,20}$",
                message = "ID number must be 8 to 20 letters or digits")
        private String postalRefNo;

        @jakarta.validation.constraints.NotBlank(message = "First name is required")
        @jakarta.validation.constraints.Size(max = 120, message = "First name is too long")
        @jakarta.validation.constraints.Pattern(regexp = VerifyRequest.NAME_PATTERN,
                message = "First name contains unsupported characters")
        private String firstName;

        @jakarta.validation.constraints.Size(max = 120, message = "Middle name is too long")
        @jakarta.validation.constraints.Pattern(regexp = VerifyRequest.OPTIONAL_NAME_PATTERN,
                message = "Middle name contains unsupported characters")
        private String middleName;

        @jakarta.validation.constraints.NotBlank(message = "Surname is required")
        @jakarta.validation.constraints.Size(max = 120, message = "Surname is too long")
        @jakarta.validation.constraints.Pattern(regexp = VerifyRequest.NAME_PATTERN,
                message = "Surname contains unsupported characters")
        private String lastName;

        @jakarta.validation.constraints.NotNull(message = "Date of birth is required")
        @jakarta.validation.constraints.Past(message = "Date of birth must be in the past")
        @org.springframework.format.annotation.DateTimeFormat(iso =
                org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
        private java.time.LocalDate dateOfBirth;

        public static VerifyForm empty() {
            return new VerifyForm();
        }

        public String getPostalRefNo() { return postalRefNo; }
        public void setPostalRefNo(String postalRefNo) { this.postalRefNo = postalRefNo; }

        public String getFirstName() { return firstName; }
        public void setFirstName(String firstName) { this.firstName = firstName; }

        public String getMiddleName() { return middleName; }
        public void setMiddleName(String middleName) { this.middleName = middleName; }

        public String getLastName() { return lastName; }
        public void setLastName(String lastName) { this.lastName = lastName; }

        public java.time.LocalDate getDateOfBirth() { return dateOfBirth; }
        public void setDateOfBirth(java.time.LocalDate dateOfBirth) { this.dateOfBirth = dateOfBirth; }
    }
}
