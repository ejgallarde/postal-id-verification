package com.postalid.verification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.time.Period;
import java.time.ZoneId;
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
    private final Sandbox sandbox = new Sandbox();

    public Verify getVerify() { return verify; }
    public Media getMedia() { return media; }
    public Storage getStorage() { return storage; }
    public Ingest getIngest() { return ingest; }
    public RateLimit getRateLimit() { return rateLimit; }
    public Audit getAudit() { return audit; }
    public Oauth getOauth() { return oauth; }
    public Sandbox getSandbox() { return sandbox; }

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

        /**
         * How long a card stays valid, counted from the date it was produced.
         *
         * <p>The enrollment export carries no expiry column, so validity is <em>derived</em>:
         * issue date plus this period. Responses flag it as derived for exactly that reason.
         * Confirm the real rule with the data owner before consumers depend on it.
         */
        private Period validityPeriod = Period.ofYears(3);

        /** Report an expired card as INVALID rather than VALID with an EXPIRED validity block. */
        private boolean treatExpiredAsInvalid = false;

        /**
         * The zone whose calendar decides whether a card has expired. Card dates are Philippine
         * calendar dates, so "today" must be too; the server's own zone (typically UTC) would
         * flip a card to EXPIRED eight hours late.
         */
        private ZoneId timeZone = ZoneId.of("Asia/Manila");

        public Set<String> getIssuedStatuses() { return issuedStatuses; }
        public void setIssuedStatuses(Set<String> issuedStatuses) { this.issuedStatuses = issuedStatuses; }

        public boolean isAllowMiddleInitial() { return allowMiddleInitial; }
        public void setAllowMiddleInitial(boolean allowMiddleInitial) { this.allowMiddleInitial = allowMiddleInitial; }

        public boolean isAllowMiddleNameOmission() { return allowMiddleNameOmission; }
        public void setAllowMiddleNameOmission(boolean v) { this.allowMiddleNameOmission = v; }

        public Period getValidityPeriod() { return validityPeriod; }
        public void setValidityPeriod(Period v) { this.validityPeriod = v; }

        public boolean isTreatExpiredAsInvalid() { return treatExpiredAsInvalid; }
        public void setTreatExpiredAsInvalid(boolean v) { this.treatExpiredAsInvalid = v; }

        public ZoneId getTimeZone() { return timeZone; }
        public void setTimeZone(ZoneId timeZone) { this.timeZone = timeZone; }
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
        private int verifyPerClientPerMinute = 300;

        /** Verification attempts per source IP per minute. */
        private int verifyPerIpPerMinute = 100;

        /** Image fetches per client per minute. */
        private int imagePerClientPerMinute = 600;

        /**
         * Image fetches per source IP per minute. A separate budget from verification: one VALID
         * result legitimately leads to two image fetches, and those must not eat into the
         * address's verification allowance.
         */
        private int imagePerIpPerMinute = 300;

        /**
         * Verifications per console operator per minute. A person reading cards at a counter
         * manages a few a minute; anything near this ceiling is a script behind a session.
         */
        private int verifyPerOperatorPerMinute = 30;

        /**
         * Requests to {@code /oauth2/token} per source IP per minute. Access tokens live five
         * minutes, so a well-behaved client needs about one request per instance every few
         * minutes; 20 leaves room for a fleet behind one NAT address while capping what one
         * address can make the server spend on BCrypt secret checks.
         */
        private int tokenPerIpPerMinute = 20;

        /**
         * Failed client authentications per client id per hour before that id is refused for a
         * while. The ceiling OWASP ASVS (V2.2.1) sets for a single credential is 100 per hour.
         * Counting per id stops a distributed guesser from spreading attempts across addresses.
         */
        private int tokenFailuresPerClientPerHour = 100;

        /**
         * Console sign-in attempts per source IP per minute. A person mistyping a password tries
         * a few times; ten a minute is far beyond that and far below what a guessing script wants.
         */
        private int loginPerIpPerMinute = 10;

        /**
         * Failed sign-ins per operator username per hour, across all addresses, before that
         * account is refused for a while. Stricter than the ASVS ceiling of 100 because operator
         * passwords are chosen by people, where client secrets are generated.
         */
        private int loginFailuresPerUserPerHour = 20;

        /**
         * Net INVALID results from one caller within the lookback window before the caller is
         * throttled harder. Blunts patient enumeration that stays under the plain per-minute
         * ceiling. A VALID result earns back one failure, but only once per distinct card in the
         * window, so replaying one known-good card cannot launder a stream of guesses.
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

        public int getImagePerIpPerMinute() { return imagePerIpPerMinute; }
        public void setImagePerIpPerMinute(int v) { this.imagePerIpPerMinute = v; }

        public int getVerifyPerOperatorPerMinute() { return verifyPerOperatorPerMinute; }
        public void setVerifyPerOperatorPerMinute(int v) { this.verifyPerOperatorPerMinute = v; }

        public int getTokenPerIpPerMinute() { return tokenPerIpPerMinute; }
        public void setTokenPerIpPerMinute(int v) { this.tokenPerIpPerMinute = v; }

        public int getTokenFailuresPerClientPerHour() { return tokenFailuresPerClientPerHour; }
        public void setTokenFailuresPerClientPerHour(int v) { this.tokenFailuresPerClientPerHour = v; }

        public int getLoginPerIpPerMinute() { return loginPerIpPerMinute; }
        public void setLoginPerIpPerMinute(int v) { this.loginPerIpPerMinute = v; }

        public int getLoginFailuresPerUserPerHour() { return loginFailuresPerUserPerHour; }
        public void setLoginFailuresPerUserPerHour(int v) { this.loginFailuresPerUserPerHour = v; }

        public int getInvalidBurstThreshold() { return invalidBurstThreshold; }
        public void setInvalidBurstThreshold(int v) { this.invalidBurstThreshold = v; }

        public Duration getInvalidLookback() { return invalidLookback; }
        public void setInvalidLookback(Duration v) { this.invalidLookback = v; }

        public Duration getInvalidPenalty() { return invalidPenalty; }
        public void setInvalidPenalty(Duration v) { this.invalidPenalty = v; }
    }

    /**
     * The external-testing sandbox. Only read when the {@code sandbox} profile is active.
     */
    public static class Sandbox {
        /**
         * Where the synthetic enrollment packets are written before being ingested. Kept on disk,
         * rather than in a temporary directory, so the exact files a tester's result came from
         * can be inspected afterwards.
         */
        private String packetDir = "./data/sandbox/packets";

        public String getPacketDir() { return packetDir; }
        public void setPacketDir(String packetDir) { this.packetDir = packetDir; }
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
