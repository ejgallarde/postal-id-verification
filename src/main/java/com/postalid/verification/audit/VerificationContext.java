package com.postalid.verification.audit;

/**
 * Who asked, from where, over which surface. Supplied by the calling layer so the service can
 * write a complete audit row without reaching into the servlet context itself.
 */
public record VerificationContext(
        String requestId,
        String clientId,
        String principal,
        Channel channel,
        String remoteIp,
        String userAgent) {

    public enum Channel {
        /** A machine client calling the REST API with a bearer token. */
        API,
        /** An operator using the web form. */
        WEB
    }
}
