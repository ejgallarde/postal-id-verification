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

    /** Who may redeem the image references this verification mints. */
    public String imageHolder() {
        return imageHolder(channel, clientId);
    }

    /**
     * Qualified by channel because OAuth client ids and operator usernames are configured
     * separately and could coincide; an operator named after an API client must not be able to
     * redeem that client's references, or the reverse.
     */
    public static String imageHolder(Channel channel, String id) {
        return id == null ? null : channel.name() + ":" + id;
    }
}
