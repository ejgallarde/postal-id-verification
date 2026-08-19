package com.postalid.verification.ingest;

/** A packet could not be ingested. Carries no field values, so it is safe to log. */
public class PacketIngestException extends RuntimeException {

    public PacketIngestException(String message) {
        super(message);
    }

    public PacketIngestException(String message, Throwable cause) {
        super(message, cause);
    }
}
