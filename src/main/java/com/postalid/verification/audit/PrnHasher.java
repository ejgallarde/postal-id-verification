package com.postalid.verification.audit;

import com.postalid.verification.config.PostalIdProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Turns a postal reference number into the peppered digest stored in the audit trail.
 *
 * <p>The pepper is a server-side secret rather than a per-row salt, because the audit table has
 * to support "how many attempts have been made against this card" — that requires the same input
 * to hash the same way. A per-environment pepper keeps the digests from being reversible with a
 * precomputed table of plausible PRNs, which a bare SHA-256 of a short structured identifier
 * absolutely would be.
 */
@Component
public class PrnHasher {

    static final String PLACEHOLDER_PEPPER = "CHANGE-ME";

    private final String pepper;

    public PrnHasher(PostalIdProperties properties) {
        this.pepper = properties.getAudit().getPrnHashPepper();
    }

    @PostConstruct
    void assertConfigured() {
        if (pepper == null || pepper.isBlank() || PLACEHOLDER_PEPPER.equals(pepper)) {
            throw new IllegalStateException(
                    "postalid.audit.prn-hash-pepper is unset or still the placeholder. "
                            + "Set a unique secret per environment (e.g. POSTALID_AUDIT_PRN_HASH_PEPPER).");
        }
        if (pepper.length() < 16) {
            throw new IllegalStateException("postalid.audit.prn-hash-pepper must be at least 16 characters.");
        }
    }

    public String hash(String normalizedPrn) {
        String input = pepper + "|" + (normalizedPrn == null ? "" : normalizedPrn);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
