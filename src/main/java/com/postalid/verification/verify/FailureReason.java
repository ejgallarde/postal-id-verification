package com.postalid.verification.verify;

/**
 * Why a verification came back INVALID.
 *
 * <p>Always written to {@code verification_audit}. Returned to a caller only when their token
 * carries the {@code postalid.diagnostics} scope; otherwise every INVALID response is identical,
 * so the endpoint cannot be walked field by field to reconstruct a stranger's details.
 *
 * <p>{@link #requestField()} maps a reason onto the request field the caller actually sent, so
 * diagnostics name {@code lastName} rather than leaking the internal enum or a column name.
 */
public enum FailureReason {

    /** No record carries that postal reference number. */
    NO_SUCH_RECORD(null),

    /** Record exists but is flagged deleted. */
    RECORD_DELETED(null),

    /** Record exists but has not reached an issued status. */
    CARD_NOT_ISSUED(null),

    /** Card was issued but is past its derived expiry, and this deployment treats that as invalid. */
    CARD_EXPIRED(null),

    SURNAME_MISMATCH("lastName"),
    FIRST_NAME_MISMATCH("firstName"),
    MIDDLE_NAME_MISMATCH("middleName"),
    DATE_OF_BIRTH_MISMATCH("dateOfBirth");

    private final String requestField;

    FailureReason(String requestField) {
        this.requestField = requestField;
    }

    /** The request field this reason refers to, or null for reasons about the record as a whole. */
    public String requestField() {
        return requestField;
    }

    public boolean isFieldMismatch() {
        return requestField != null;
    }
}
