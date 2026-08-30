# Postal ID Verification Tool

A web console and secured REST API for validating Postal Identification cards against the
enrollment database, returning **VALID** or **INVALID** along with the cardholder photograph and
signature captured at enrollment.

Java 17 · Spring Boot 3.5 · Thymeleaf · Spring Authorization Server · Flyway · H2 (PostgreSQL-ready)

---

## Handling real data

Enrollment packets are among the most sensitive records an organization holds: a face photograph,
four fingerprints, birth and marriage certificates, a mobile number and a government reference
number, all belonging to one identifiable person.

- **`files/` and `data/` are git-ignored and must stay that way.** Nothing from a real packet is
  ever committed.
- **Tests never touch real data.** They generate a synthetic packet
  ([`SyntheticPacket`](src/test/java/com/postalid/verification/testsupport/SyntheticPacket.java))
  and run against that.
- **Fingerprints and document scans are stored but unreachable.** No scope, no reference and no
  route can retrieve them. Only the photograph and signature are servable.
- **Logs contain no personal data.** The audit trail stores a peppered SHA-256 of the reference
  number, never the number itself.

---

## Quick start

Requires JDK 17+ and Maven 3.9+. No database or container runtime needed — H2 runs in-process.

```bash
# Load the enrollment packets in ./files, then start serving.
mvn spring-boot:run -Dspring-boot.run.profiles=dev,ingest

# Subsequent runs, without re-ingesting.
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

| | |
|---|---|
| Console | <http://localhost:8080> — sign in as `operator` / `dev-only-password` |
| API docs | <http://localhost:8080/swagger-ui.html> |
| OpenAPI | <http://localhost:8080/v3/api-docs> |
| Database | <http://localhost:8080/h2-console> — JDBC `jdbc:h2:file:./data/postalid`, user `sa`, no password |

The `dev` profile ships well-known placeholder credentials so a fresh checkout runs. **It must
never be active on a deployed instance** — see [Deploying](#deploying).

### Try the API

```bash
TOKEN=$(curl -s -u 'postalid-demo-client:dev-only-secret-change-me' \
  -d 'grant_type=client_credentials' -d 'scope=postalid.verify postalid.images' \
  http://localhost:8080/oauth2/token | jq -r .access_token)

curl -s -X POST http://localhost:8080/api/v1/verify \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"postalRefNo":"...","firstName":"...","middleName":"...",
       "lastName":"...","dateOfBirth":"1990-01-31"}' | jq .
```

Full contract: **[docs/API.md](docs/API.md)**.

---

## How verification works

A submission is **VALID** only when a record exists for that postal reference number, is not
deleted, has reached an issued status (`CDE`), and the surname, first name, middle name and date
of birth all match.

Names are compared on a normalized form — trimmed, uppercased, accents decomposed and dropped,
`.`/`-`/`'` removed, whitespace collapsed — computed by
[`FieldNormalizer`](src/main/java/com/postalid/verification/verify/FieldNormalizer.java). The
ingester writes the same normalized form into the `*_norm` columns, so stored and submitted values
can never be normalized differently. A middle **initial** is accepted where the record holds a
full middle name, because many cards print only the initial.

### What a VALID response carries

The cardholder details **as printed on the card** — name, date of birth, gender, nationality,
home address, issuing post office — plus the card validity window and short-lived references to
the photograph and signature.

The selection follows one rule: return only what the card itself shows. The verifier is holding
that card, so echoing its contents lets them spot a tampered one while disclosing nothing they
could not already read. Parents' names, mobile number, email, TIN, GSIS, PhilHealth, HDMF,
employer, work address and place of birth are all on the record and none are returned — place of
birth and the government identifiers especially, since both are used as knowledge-based
authentication secrets elsewhere.

### Card validity is derived, and says so

The enrollment export has **no expiry column**. Validity is computed as the card production date
— the issued entry in the status trail, falling back to enrolment date — plus
`postalid.verify.validity-period`, default three years. Every response flags it `derived: true`.

An expired card still returns `VALID` with `validity.status = EXPIRED`: the identity checked out,
which is a different fact from the card being current. Collapsing the two would stop a caller
telling a lapsed card from a forged one. Set `postalid.verify.treat-expired-as-invalid` for the
stricter reading.

### Every refusal looks identical

Unknown reference number, un-issued card, misspelt surname: all return the same bare `INVALID`,
with no indication of which. The reason goes to the audit trail instead.

Clients granted the `postalid.diagnostics` scope additionally receive a `reason` object naming
**every** mismatched field, not just the first. That scope is granted per client precisely because
field-level detail is what would otherwise let any credential holder confirm guesses about a
stranger one field at a time. Without that scope the endpoint reveals nothing — not even whether
a reference number exists, which would otherwise make it a directory of who holds a Postal ID.
For the same reason the not-found path still runs a comparison against a decoy record, so it
cannot be identified by being measurably faster.

---

## Architecture

```
web/      Thymeleaf console, rate-limit filter, request context
api/      REST controllers, DTOs, RFC 9457 error handling
verify/   FieldNormalizer, MatchRules, VerificationService, VerificationFacade
media/    TIFF->PNG transcoding, image reference minting and redemption
ingest/   Packet parsing and loading (profile-gated)
storage/  Filesystem blob store with path containment
audit/    Peppered hashing and the verification audit trail
domain/   JPA entities and repositories
config/   Security chains, authorization server, OpenAPI, properties
```

Two security filter chains serve the two kinds of caller, because they need opposite settings: the
API chain is stateless and bearer-token driven with no session for CSRF to protect; the web chain
is session-based with CSRF enabled. A third chain, ahead of both, serves only the OAuth2 protocol
endpoints.

Both surfaces reach verification through
[`VerificationFacade`](src/main/java/com/postalid/verification/verify/VerificationFacade.java),
so the rule that image references are minted **only** on a `VALID` result lives in one place.

### Data model

| Table | Holds |
|---|---|
| `cardholder` | One enrollment record: ~90 demographic fields plus normalized name columns |
| `cardholder_document` | Record → captured files, with SHA-256, storage kind and location |
| `document_type` | Asset codes, and the `is_publicly_retrievable` flag that gates the API |
| `card_status_history` | The enrollment status trail |
| `verification_audit` | Every attempt, with a hashed reference number |
| `image_access_token` | Short-lived image capabilities, stored hashed |
| `oauth2_*` | Registered clients and issued authorizations |

Servable image bytes live under `postalid.storage.root`; the database holds the pointer and digest.
Everything else is referenced in place — see [What gets copied](#what-gets-copied-and-what-does-not).

### Reading an enrollment packet

Every file in a packet is named `{RecordID}_{Suffix}.{ext}`. Two identifiers are easy to confuse:
`RecordID` is the internal packet key and filename prefix, while **`PostalRefNo` is the number
printed on the card** and the value a verifying party types in.

The ingester takes file extensions from disk rather than from the packet's `lateSub_*.txt`
manifest, which lists assets as `.jpg` that actually ship as `.tiff`.

---

## Building and testing

```bash
mvn clean verify              # compile and run all tests
mvn test -Dtest=MatchRulesTest        # one class
mvn test -Dtest='MatchRulesTest#*middle*'   # one pattern
mvn verify -Psecurity-scan -DnvdApiKey=<key>   # OWASP dependency scan
```

The suite covers the normalization and matching truth table, packet ingest, the bilevel LZW TIFF
transcode, blob-store path containment, rate limiting, and the API and console end to end —
including that every `INVALID` body is identical and that no fingerprint is ever mintable.

### Regenerating the OpenAPI document

`docs/openapi.json` is a committed snapshot so contract changes show up in review:

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev &
curl -s http://localhost:8080/v3/api-docs -o docs/openapi.json
```

---

## Loading enrollment packets

Point `postalid.ingest.source-dir` at the root of a drop and run with the `ingest` profile:

```bash
POSTALID_INGEST_DIR=/secure/packets \
  mvn spring-boot:run -Dspring-boot.run.profiles=dev,ingest
```

The tree is searched recursively (to `postalid.ingest.max-depth`, default 5), so the expected
layout of one subfolder per enrollee works, with or without a batch level above it:

```
/secure/packets/
  2026-Q1/
    0119990101ZZZTEST0001/
      0119990101ZZZTEST0001_demographics.csv
      0119990101ZZZTEST0001_Photo.jpg
      …
```

Discovery keys on finding a `*_demographics.csv`, **not** on the folder's name. The record id is
read from inside the CSV and assets are located relative to the CSV's own directory, so an
unexpectedly-named folder still loads. A flat directory works equally well. Symbolic links are not
followed.

Ingest is idempotent — re-running updates in place. A packet that fails is logged and skipped so
one bad record cannot block a batch; set `postalid.ingest.fail-fast=true` to abort on the first
failure instead.

### What gets copied, and what does not

Only the two files the API can serve are copied into the blob store. Everything else is recorded
with its metadata, SHA-256 and a pointer, and left in the archive:

| Storage kind | Documents | Bytes copied |
|---|---|---|
| `MANAGED` | Photo, Signature | Yes — served on demand |
| `EXTERNAL` | Birth/marriage certificates, receipts, forms, fingerprints | No — metadata and pointer only |

On the sample packet that is **26 KB copied out of 3.40 MB — 0.75%**. Per 1,000 enrollees: ~25 MB
of hot storage against ~3.3 GB of archive. No route can return an `EXTERNAL` document, so copying
them would duplicate almost the whole archive into a tier that never reads it.

`source_path` is recorded **relative to the ingest root**, not absolute, so an archive that is
later moved or remounted stays resolvable as *current root + source_path*. The digest is computed
for every file either way, so an archived document can still be checked against what was captured.

One consequence to know: if a document type is ever made publicly retrievable, rows already
ingested as `EXTERNAL` have no local bytes and will 404. Re-ingest those packets rather than
flipping the flag alone.

---

## Configuration

All settings live under `postalid.*`. The ones that matter most:

| Property | Default | Purpose |
|---|---|---|
| `postalid.audit.prn-hash-pepper` | *(none)* | **Required.** Server-side secret for audit hashing; startup fails without it |
| `postalid.verify.issued-statuses` | `CDE` | Statuses that count as an issued card |
| `postalid.verify.allow-middle-initial` | `true` | Accept a middle initial for a full middle name |
| `postalid.verify.allow-middle-name-omission` | `false` | Accept an omitted middle name |
| `postalid.verify.validity-period` | `P3Y` | Derived card validity, counted from the production date |
| `postalid.verify.treat-expired-as-invalid` | `false` | Report an expired card as INVALID rather than VALID + EXPIRED |
| `postalid.media.token-ttl` | `5m` | Image reference lifetime |
| `postalid.media.token-max-uses` | `5` | Fetches allowed per reference |
| `postalid.storage.root` | `./data/blobstore` | Where servable image bytes live |
| `postalid.ingest.source-dir` | `./files` | Root of a packet drop, searched recursively |
| `postalid.ingest.max-depth` | `5` | How deep below the source root to search |
| `postalid.ingest.fail-fast` | `false` | Abort the batch on the first bad packet |
| `postalid.rate-limit.*` | see below | Per-client and per-address budgets |
| `postalid.oauth.issuer` | *(none)* | Public base URL; pins the `iss` claim |
| `postalid.oauth.jwk.keystore-location` | *(none)* | RSA signing key; ephemeral if unset |
| `postalid.web.public-api-docs` | `false` | Whether Swagger UI is readable without signing in |

Rate limits default to 300 verifications per client per minute, 100 per source address, and 600
image fetches per client. They are held in-process: behind more than one instance the effective
limit multiplies by the instance count, so divide the configured numbers accordingly until a
shared backend is introduced.

---

## Deploying

The `dev` profile exists for laptops. A real deployment needs all of the following.

**1. A signing keystore.** Without one, an ephemeral RSA key is generated at startup — every token
dies on restart, and a second instance cannot validate the first's tokens.

```bash
keytool -genkeypair -alias postalid-jwt -keyalg RSA -keysize 2048 \
  -storetype PKCS12 -keystore jwt-keystore.p12 -validity 730 \
  -dname "CN=Postal ID Verification"
```

**2. TLS.** `server.ssl.enabled` defaults to `true` outside dev. Supply a certificate from your
own CA, not a self-signed one.

**3. Secrets from the environment.** Never in a committed file:

```bash
export POSTALID_AUDIT_PRN_HASH_PEPPER=<32+ random chars>
export POSTALID_JWT_KEYSTORE=file:/secure/jwt-keystore.p12
export POSTALID_JWT_KEYSTORE_PASSWORD=<...>
export POSTALID_SSL_KEYSTORE=file:/secure/tls.p12
export POSTALID_SSL_KEYSTORE_PASSWORD=<...>
export POSTALID_ISSUER=https://verify.example.gov.ph
export POSTALID_STORAGE_ROOT=/secure/blobstore
```

**4. Real client and operator credentials**, declared under `postalid.oauth.clients` and
`postalid.web.operators` with secrets injected from the environment. Client secrets are stored
BCrypt-hashed.

**5. Behind a proxy**, set `server.forward-headers-strategy=native` (or `framework`) so the
container resolves the client address from headers only your proxy can set. The application
deliberately reads the raw socket address otherwise, because trusting `X-Forwarded-For` would let
any caller reset its own rate-limit bucket.

**6. Consider PostgreSQL.** H2 is fine for a single instance; anything more wants a real database.
Uncomment the `postgresql` and `flyway-database-postgresql` dependencies in `pom.xml` and run with
`--spring.profiles.active=postgres`. Flyway picks the PostgreSQL-flavoured OAuth schema
automatically through the `{vendor}` location; the rest of the migrations are portable.

### Security controls in place

TLS with HSTS · OAuth2 client credentials with scoped, short-lived, audience-pinned JWTs ·
BCrypt(12) secret hashing · CSRF on the session chain · a CSP that permits nothing off-origin ·
allow-list input validation · parameterized queries throughout · uniform `INVALID` responses with
decoy-record timing · dual-axis rate limiting · path-containment on the blob store · pixel budgets
on image decoding · `no-store` on every response carrying personal data · actuator reduced to
`/health` · full audit trail with no raw identifiers.

---

## Known gaps

- **Two document codes are unconfirmed.** `PC` and `E6Form` are seeded `UNCONFIRMED` in
  `document_type`. Both are recorded and neither is retrievable, so this blocks nothing — but the
  descriptions should be corrected once the data owner confirms them.
- **Ingest is sequential and non-incremental.** Every run re-reads and re-hashes each file it
  finds. Sized for quarterly batches of thousands, where a run is minutes; a continuous feed or a
  much larger backfill would want skip-if-unchanged and parallelism.
- **Processed packets are not archived or moved**, so a re-run reprocesses the whole drop. Correct,
  because ingest is idempotent — just slower than it needs to be.
- **Rate limiting is in-process.** See the note under [Configuration](#configuration).
- **Operator accounts are in-memory.** Fine for a handful of operators; a larger deployment should
  replace the `UserDetailsService` bean with a directory-backed one.
- **Capture timestamps are ambiguous in the source.** `CapturedBy` in the sample export holds what
  looks like a date-and-times triple, suggesting a column misalignment upstream. Those three
  fields are stored verbatim as strings rather than parsed into something that would be wrong.
