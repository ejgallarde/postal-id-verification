package com.postalid.verification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Everything tunable about verification, media handling, ingest and throttling. */
@ConfigurationProperties(prefix = "postalid")
public class PostalIdProperties {

    private final Verify verify = new Verify();
    private final Media media = new Media();
    private final Storage storage = new Storage();
    private final Ingest ingest = new Ingest();
    private final RateLimit rateLimit = new RateLimit();
    private final Audit audit = new Audit();
    private final Oauth oauth = new Oauth();

    public Verify getVerify() { return verify; }
    public Media getMedia() { return media; }
    public Storage getStorage() { return storage; }
    public Ingest getIngest() { return ingest; }
    public RateLimit getRateLimit() { return rateLimit; }
    public Audit getAudit() { return audit; }
    public Oauth getOauth() { return oauth; }

    public static class Verify {
        /**
         * Enrollment statuses that mean a card was actually issued. A record existing in the
         * database is not on its own evidence of a valid card, so verification gates on this.
         */
        private Set<String> issuedStatuses = Set.of("CDE");

        /** Accept a single-letter middle initial where the stored record holds a full middle name. */
        private boolean allowMiddleInitial = true;

        /** Accept an omitted middle name where the stored record holds a full middle name. */
        private boolean allowMiddleNameOmission = false;

        public Set<String> getIssuedStatuses() { return issuedStatuses; }
        public void setIssuedStatuses(Set<String> issuedStatuses) { this.issuedStatuses = issuedStatuses; }

        public boolean isAllowMiddleInitial() { return allowMiddleInitial; }
        public void setAllowMiddleInitial(boolean allowMiddleInitial) { this.allowMiddleInitial = allowMiddleInitial; }

        public boolean isAllowMiddleNameOmission() { return allowMiddleNameOmission; }
        public void setAllowMiddleNameOmission(boolean v) { this.allowMiddleNameOmission = v; }
    }

    public static class Media {
        /** How long an image reference stays usable after a VALID verification. */
        private Duration tokenTtl = Duration.ofMinutes(5);

        /** Fetches allowed per reference, so a page reload or a retried load still works. */
        private int tokenMaxUses = 5;

        /** Guard against decompression bombs when transcoding an untrusted TIFF. */
        private int maxImagePixels = 40_000_000;

        public Duration getTokenTtl() { return tokenTtl; }
        public void setTokenTtl(Duration tokenTtl) { this.tokenTtl = tokenTtl; }

        public int getTokenMaxUses() { return tokenMaxUses; }
        public void setTokenMaxUses(int tokenMaxUses) { this.tokenMaxUses = tokenMaxUses; }

        public int getMaxImagePixels() { return maxImagePixels; }
        public void setMaxImagePixels(int maxImagePixels) { this.maxImagePixels = maxImagePixels; }
    }

    public static class Storage {
        /** Root of the blob store. Holds extracted PII, so keep it outside the repository. */
        private String root = "./data/blobstore";

        public String getRoot() { return root; }
        public void setRoot(String root) { this.root = root; }
    }

    public static class Ingest {
        /**
         * Root scanned for packets. Searched recursively, because packets arrive as one
         * subfolder per enrollee; a flat directory works equally well.
         */
        private String sourceDir = "./files";

        /**
         * How deep to search below the source root. One subfolder per enrollee needs 2; the
         * default leaves room for a batch or date level above that without being an invitation
         * to walk an entire filesystem.
         */
        private int maxDepth = 5;

        /** Fail the whole run on the first bad packet, rather than skipping it. */
        private boolean failFast = false;

        public String getSourceDir() { return sourceDir; }
        public void setSourceDir(String sourceDir) { this.sourceDir = sourceDir; }

        public int getMaxDepth() { return maxDepth; }
        public void setMaxDepth(int maxDepth) { this.maxDepth = maxDepth; }

        public boolean isFailFast() { return failFast; }
        public void setFailFast(boolean failFast) { this.failFast = failFast; }
    }

    public static class RateLimit {
        private boolean enabled = true;

        /** Verification attempts per client per minute. */
        private int verifyPerClientPerMinute = 60;

        /** Verification attempts per source IP per minute. */
        private int verifyPerIpPerMinute = 20;

        /** Image fetches per client per minute. */
        private int imagePerClientPerMinute = 120;

        /**
         * Consecutive INVALID results from one client within the lookback window before the
         * client is throttled harder. Blunts patient enumeration that stays under the plain
         * per-minute ceiling.
         */
        private int invalidBurstThreshold = 20;
        private Duration invalidLookback = Duration.ofMinutes(10);
        private Duration invalidPenalty = Duration.ofMinutes(5);

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public int getVerifyPerClientPerMinute() { return verifyPerClientPerMinute; }
        public void setVerifyPerClientPerMinute(int v) { this.verifyPerClientPerMinute = v; }

        public int getVerifyPerIpPerMinute() { return verifyPerIpPerMinute; }
        public void setVerifyPerIpPerMinute(int v) { this.verifyPerIpPerMinute = v; }

        public int getImagePerClientPerMinute() { return imagePerClientPerMinute; }
        public void setImagePerClientPerMinute(int v) { this.imagePerClientPerMinute = v; }

        public int getInvalidBurstThreshold() { return invalidBurstThreshold; }
        public void setInvalidBurstThreshold(int v) { this.invalidBurstThreshold = v; }

        public Duration getInvalidLookback() { return invalidLookback; }
        public void setInvalidLookback(Duration v) { this.invalidLookback = v; }

        public Duration getInvalidPenalty() { return invalidPenalty; }
        public void setInvalidPenalty(Duration v) { this.invalidPenalty = v; }
    }

    public static class Audit {
        /**
         * Server-side pepper mixed into the PRN before hashing for the audit trail. Must be
         * supplied per environment; startup fails if this is left at the placeholder.
         */
        private String prnHashPepper = "CHANGE-ME";

        public String getPrnHashPepper() { return prnHashPepper; }
        public void setPrnHashPepper(String prnHashPepper) { this.prnHashPepper = prnHashPepper; }
    }

    public static class Oauth {
        /** Clients registered (or refreshed) at startup by ClientRegistrationBootstrap. */
        private Map<String, Client> clients = new LinkedHashMap<>();

        private Duration accessTokenTtl = Duration.ofMinutes(5);

        /** Audience claim stamped on issued tokens and required of presented ones. */
        private String audience = "postal-id-verification-api";

        /**
         * Fixed issuer identifier. Left unset, the issuer is derived per request from the Host
         * header, which is convenient locally but means the {@code iss} claim cannot be pinned.
         * Set it to the public base URL in any real deployment.
         */
        private String issuer;

        private final Jwk jwk = new Jwk();

        public Map<String, Client> getClients() { return clients; }
        public void setClients(Map<String, Client> clients) { this.clients = clients; }

        public Duration getAccessTokenTtl() { return accessTokenTtl; }
        public void setAccessTokenTtl(Duration accessTokenTtl) { this.accessTokenTtl = accessTokenTtl; }

        public String getAudience() { return audience; }
        public void setAudience(String audience) { this.audience = audience; }

        public String getIssuer() { return issuer; }
        public void setIssuer(String issuer) { this.issuer = issuer; }

        public Jwk getJwk() { return jwk; }

        /**
         * The RSA key pair that signs access tokens.
         *
         * <p>Leave the keystore unset and an ephemeral key is generated at startup, which is fine
         * for a single dev instance but invalidates every live token on restart and cannot work
         * across more than one node. Any real deployment supplies a keystore.
         */
        public static class Jwk {
            private String keystoreLocation;
            private String keystorePassword;
            private String keyAlias = "postalid-jwt";
            private String keyPassword;

            public String getKeystoreLocation() { return keystoreLocation; }
            public void setKeystoreLocation(String v) { this.keystoreLocation = v; }

            public String getKeystorePassword() { return keystorePassword; }
            public void setKeystorePassword(String v) { this.keystorePassword = v; }

            public String getKeyAlias() { return keyAlias; }
            public void setKeyAlias(String v) { this.keyAlias = v; }

            public String getKeyPassword() { return keyPassword; }
            public void setKeyPassword(String v) { this.keyPassword = v; }
        }

        public static class Client {
            private String name;
            /** Plaintext only in configuration; stored BCrypt-hashed. Inject via environment. */
            private String secret;
            private List<String> scopes = List.of();

            public String getName() { return name; }
            public void setName(String name) { this.name = name; }

            public String getSecret() { return secret; }
            public void setSecret(String secret) { this.secret = secret; }

            public List<String> getScopes() { return scopes; }
            public void setScopes(List<String> scopes) { this.scopes = scopes; }
        }
    }
}
