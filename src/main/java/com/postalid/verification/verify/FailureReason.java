package com.postalid.verification.verify;

/**
 * Why a verification came back INVALID.
 *
 * <p>This never reaches the caller. It is written to {@code verification_audit} so operators can
 * investigate abuse and data-quality problems, while the API keeps every INVALID response
 * byte-identical. Telling a caller which field was wrong would turn the endpoint into a tool for
 * reconstructing a stranger's details one request at a time.
 */
public enum FailureReason {

    /** No record carries that postal reference number. */
    NO_SUCH_RECORD,

    /** Record exists but is flagged deleted. */
    RECORD_DELETED,

    /** Record exists but has not reached an issued status. */
    CARD_NOT_ISSUED,

    SURNAME_MISMATCH,
    FIRST_NAME_MISMATCH,
    MIDDLE_NAME_MISMATCH,
    DATE_OF_BIRTH_MISMATCH
}
