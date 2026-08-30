# PIVOT API

**PHLPost Identity Verification & Onboarding Toolkit**

Version 1.0.0 · OpenAPI 3.1 · Base path `/api/v1`

This document is the contract for machine clients. A live, browsable copy is generated from the
running service at `/swagger-ui.html`, with the raw document at `/v3/api-docs`.

---

## Contents

1. [What the API does](#1-what-the-api-does)
2. [Authentication](#2-authentication)
3. [POST /api/v1/verify](#3-post-apiv1verify)
4. [GET /api/v1/images/{reference}](#4-get-apiv1imagesreference)
5. [Errors](#5-errors)
6. [Rate limits](#6-rate-limits)
7. [Worked example](#7-worked-example)
8. [Client implementation notes](#8-client-implementation-notes)

---

## 1. What the API does

You submit the details printed on a Postal ID card. The service answers **`VALID`** or
**`INVALID`**.

`VALID` means all of the following hold:

- a record exists with that postal reference number,
- the record is not flagged deleted,
- the record has reached an issued status (`CDE` by default), and
- the surname, first name, middle name and date of birth all match.

Anything else is `INVALID`.

A `VALID` response returns the cardholder details printed on the card, the card validity window,
and short-lived references to the photograph and signature.

### What `INVALID` will not tell you

Every failing case returns a byte-identical body. The API will not reveal whether a reference
number exists, whether a card was issued, or which field was wrong.

This is deliberate. A verification endpoint that explains its refusals is a tool for
reconstructing a stranger's identity: submit a real reference number with a guessed surname, and
a helpful error confirms the guess. Distinguishing "no such card" from "wrong name" would also
turn the endpoint into a directory of who holds a Postal ID.

The reason for each refusal *is* recorded, in the service's audit trail, where operators can
investigate it.

**One exception, granted per client:** a token carrying the `postalid.diagnostics` scope receives
a `reason` object naming every mismatched field. See
[Diagnostics](#diagnostics-naming-the-mismatched-fields). If you have not been granted that
scope, everything above applies to you unchanged.

### Matching rules

| Field | Rule |
|---|---|
| `postalRefNo` | Exact, after normalization |
| `lastName` | Exact, after normalization |
| `firstName` | Exact, after normalization |
| `middleName` | Full name, **or** a single letter matching the stored initial, **or** absent if the record has none |
| `dateOfBirth` | Exact calendar date |

Normalization, applied identically to your input and to the stored value: trim surrounding
whitespace, uppercase, decompose accented characters and drop the accents, remove `.`, `-`, `'`
and `,`, then collapse runs of whitespace. So `  josé o'brien-smith  ` and `JOSE OBRIENSMITH`
compare equal.

Two consequences worth knowing:

- **A middle initial is accepted.** Many cards print only the initial. `R`, `R.` and `REYES` all
  match a stored `REYES`.
- **Omitting a middle name the record has is rejected**, by default. The service can be configured
  to tolerate it (`postalid.verify.allow-middle-name-omission`), but the shipped default does not.

---

## 2. Authentication

OAuth 2.0 **client credentials** (RFC 6749 §4.4). You are issued a client id and secret; you
exchange them for a short-lived access token and send that token as a bearer credential.

### Scopes

| Scope | Grants |
|---|---|
| `postalid.verify` | Submitting verification requests |
| `postalid.images` | Fetching a verified cardholder's photo and signature |
| `postalid.diagnostics` | Receiving the reason and mismatched fields on an INVALID result |

Scopes are independent. A client granted only `postalid.verify` receives image references in a
`VALID` response but gets `403` when it tries to fetch them — request only what you need.

### Getting a token

```
POST /oauth2/token
Authorization: Basic base64(client_id:client_secret)
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials&scope=postalid.verify%20postalid.images
```

```json
{
  "access_token": "eyJraWQiOiJhMTc1NTM2Mi1iODdhLTRkOGEtOWUzNi02MGFkNTQwMDA2NDYi...",
  "scope": "postalid.images postalid.verify",
  "token_type": "Bearer",
  "expires_in": 299
}
```

`client_secret_post` is also accepted if your HTTP client cannot do Basic auth — send `client_id`
and `client_secret` as form fields instead.

The token is a signed JWT. Its default lifetime is **five minutes**; the public keys for verifying
it are at `/oauth2/jwks`.

Requesting a scope you have not been granted returns `400 invalid_scope`. A wrong secret returns
`401`.

### Using a token

```
Authorization: Bearer <access_token>
```

---

## 3. POST /api/v1/verify

Requires scope `postalid.verify`.

### Request

```json
{
  "postalRefNo": "A11230000001",
  "firstName": "JUAN",
  "middleName": "SANTOS",
  "lastName": "DELA CRUZ",
  "dateOfBirth": "1990-01-31"
}
```

| Field | Type | Required | Constraints |
|---|---|---|---|
| `postalRefNo` | string | yes | 8–20 characters, letters and digits only |
| `firstName` | string | yes | ≤ 120 chars; letters, spaces, `.`, `-`, `'` |
| `middleName` | string | no | ≤ 120 chars, same character set; omit or send `null` if the card shows none |
| `lastName` | string | yes | ≤ 120 chars, same character set |
| `dateOfBirth` | string | yes | ISO-8601 `yyyy-MM-dd`, must be in the past |

Input is validated against an allow-list and **rejected** if it does not fit — nothing is silently
stripped. A name containing `<`, `>`, digits or control characters produces `400`, not a sanitized
lookup.

### Response — VALID

`200 OK`

```json
{
  "result": "VALID",
  "verifiedAt": "2026-08-30T08:35:04.526Z",
  "requestId": "7038dc89-3bf6-4b9e-b423-309586eef8d1",
  "cardholder": {
    "postalRefNo": "A11230000001",
    "firstName":   "JUAN",
    "middleName":  "SANTOS",
    "lastName":    "DELA CRUZ",
    "suffix":      null,
    "dateOfBirth": "1990-01-31",
    "gender":      "F",
    "nationality": "Filipino",
    "address": {
      "houseBldgLot": "BLK 7 LOT 2",
      "subdivision":  "GREENVIEW SUBDIVISION",
      "barangay":     "BARANGAY SAN ROQUE",
      "city":         "City of Calamba",
      "province":     "BTG",
      "postalCode":   "4027",
      "country":      "PHL"
    },
    "issuingPostOffice": null,
    "enrolledOn":        "2024-02-01"
  },
  "validity": {
    "issuedOn":        "2024-02-14",
    "expiresOn":       "2027-02-14",
    "status":          "ACTIVE",
    "daysUntilExpiry": 1069,
    "derived":         true
  },
  "images": {
    "photo": "/api/v1/images/Op4OPoAUFkBZjIVlmfXEHtRPpun-I3_5WoOdpU20_Z8",
    "signature": "/api/v1/images/0ca-q-A1xDzxkTWwoakbT8bwGQba4ZeXWlRnhkUhNQc"
  },
  "imagesExpireIn": 300
}
```

#### What the cardholder block contains, and what it never will

The rule is **only what is printed on the card**. You are holding that card, so echoing its
contents lets you spot a tampered one while disclosing nothing you could not already read.

Present on the enrollment record and deliberately **never returned**: parents' names, mobile
number, contact number, email, TIN, CRN, GSIS, PhilHealth, HDMF, employer, occupation, work
address, place of birth, physical description, payment records, and every internal processing
field. Place of birth and the government identifiers are the sharpest omissions — both are widely
used as knowledge-based authentication secrets, so handing them to a verifying party would
actively enable impersonation.

#### Card validity is derived

**The source data has no expiry column.** `expiresOn` is computed as the card production date
plus a configured validity period (three years by default), which is why every response carries
`derived: true`. Treat it as indicative, not as an issued fact.

| `validity.status` | Meaning |
|---|---|
| `ACTIVE` | Within the derived validity window |
| `EXPIRED` | Past the derived expiry date |
| `UNKNOWN` | No production or enrolment date on record, so nothing can be said |

An expired card still returns **`VALID`**. The identity checked out, which is a different fact
from the card being current — and collapsing the two would stop you telling a lapsed card from a
forged one. **Read `validity.status` and apply your own policy.**

### Response — INVALID

`200 OK`

```json
{
  "result": "INVALID",
  "verifiedAt": "2026-08-18T08:19:43.889997Z",
  "requestId": "496e7649-0ff3-40ba-9e46-a00584a5ca81"
}
```

The `cardholder`, `validity`, `images` and `imagesExpireIn` keys are **absent**, not null.

#### Diagnostics: naming the mismatched fields

Clients granted `postalid.diagnostics` receive a `reason` object on every INVALID:

```json
{
  "result": "INVALID",
  "verifiedAt": "2026-08-30T08:36:34.479Z",
  "requestId": "b6b8b4de-1362-4881-936d-70b21e0bc769",
  "reason": {
    "code": "FIELD_MISMATCH",
    "mismatchedFields": ["lastName", "firstName", "dateOfBirth"],
    "message": "3 field(s) did not match the stored record"
  }
}
```

`mismatchedFields` lists **every** field that failed, not just the first, so a caller correcting
one typo is not sent round the loop for a second they were never told about.

| `reason.code` | Meaning |
|---|---|
| `FIELD_MISMATCH` | One or more submitted fields did not match; see `mismatchedFields` |
| `NO_SUCH_RECORD` | No record carries that reference number |
| `CARD_NOT_ISSUED` | The record exists but no card was ever produced |
| `RECORD_DELETED` | The record exists but has been deleted |
| `CARD_EXPIRED` | Past expiry, where the deployment treats that as invalid |

Without the scope, the `reason` key is absent entirely and all failures remain identical. The
scope is granted per client because field-level detail is exactly what makes the endpoint usable
for confirming guesses about a stranger — request it only if you genuinely need it, and expect to
justify it.

### Fields

| Field | Description |
|---|---|
| `result` | `VALID` or `INVALID` |
| `verifiedAt` | When the check ran, ISO-8601 UTC |
| `requestId` | Correlation id, also on the `X-Request-Id` header and in the audit trail. Quote it in support requests |
| `images` | Present only on `VALID`. Relative URLs, keyed `photo` and `signature`. A key is absent if that image was never captured |
| `imagesExpireIn` | Present only on `VALID`. Seconds until the references stop working |

### Why INVALID is 200 and not 404

The request was well-formed, authorized, and answered. `INVALID` is the answer, not a failure.
Reserving 4xx for actual protocol faults means your client can treat 4xx and 5xx uniformly as
"retry or fix the call" without having to special-case a legitimate negative result.

**Branch on `result`, never on the status code.**

---

## 4. GET /api/v1/images/{reference}

Requires scope `postalid.images`.

Streams the photograph or signature behind a reference from a `VALID` response. There is no way to
address a cardholder or a document directly — a reference is the only input, and it is 256 bits of
randomness, so it cannot be guessed or enumerated.

```
GET /api/v1/images/vi1_dsUuf6LqRVgoSbSLBW_nsUcF4Zr8OFYnzWeoe-4
Authorization: Bearer <access_token>
```

`200 OK` with the image bytes and:

```
Content-Type: image/jpeg          (or image/png)
Cache-Control: no-store
Content-Disposition: inline
X-Content-Type-Options: nosniff
```

### Content types

**Read the `Content-Type` header.** It varies by image, and by design:

| Image | Stored as | Served as |
|---|---|---|
| Photograph | JPEG | `image/jpeg`, unchanged |
| Signature | Bilevel LZW TIFF | `image/png`, converted on the way out |

Signatures and document scans are captured as TIFF, which no browser renders, so they are
transcoded to PNG. The photograph is already web-safe and is passed through untouched, because
re-encoding it as PNG would multiply its size for no benefit.

### Reference lifetime

- **Expiry**: 5 minutes from issue (`imagesExpireIn` gives the exact figure).
- **Uses**: 5 fetches per reference, so a page reload or a retried load still works.
- **Binding**: one reference addresses one image of one cardholder.

A reference that is unknown, expired, exhausted or revoked all return the same `404`. They are
indistinguishable on purpose: telling them apart would confirm which references had once been
real.

### What can never be retrieved

Enrollment packets also contain fingerprints (WSQ), birth and marriage certificates, official
receipts and enrollment forms. These are stored and mapped to the record, but **no scope and no
reference can retrieve them**. Only the photograph and signature are ever servable.

---

## 5. Errors

Errors are RFC 9457 problem documents, `Content-Type: application/problem+json`.

```json
{
  "type": "https://postalid.example/problems/validation-failed",
  "title": "Validation Failed",
  "status": 400,
  "detail": "One or more fields are missing or malformed.",
  "errors": {
    "postalRefNo": "Postal reference number must be 8 to 20 letters or digits",
    "firstName": "First name is required"
  }
}
```

| Status | `type` suffix | Cause |
|---|---|---|
| `400` | `validation-failed` | A field is missing or breaks its constraints. `errors` names each one |
| `400` | `malformed-request` | The body is not JSON matching the schema |
| `401` | — | Token missing, expired, malformed, or not for this audience |
| `403` | — | Token valid but lacks the required scope |
| `404` | — | Image reference unknown, expired or exhausted |
| `413` | `payload-too-large` | Request body above the size cap; refused before it is parsed |
| `415` | — | Unsupported `Content-Type`; send `application/json` |
| `406` | — | Requested representation is unavailable; this API returns JSON |
| `405` | — | Wrong HTTP method |
| `429` | `rate-limited` | Budget exceeded. See `Retry-After` and `retryAfterSeconds` |
| `500` | `internal-error` | Unexpected fault. `reference` correlates with the server log |

Validation errors name your own fields, because that is how you fix the call. Everything else is
generic: a `500` returns a reference and nothing more, with the detail confined to the server log.

---

## 6. Rate limits

Two budgets apply to verification, and both must pass:

| Budget | Default |
|---|---|
| Verifications per client per minute | 300 |
| Verifications per source address per minute | 100 |
| Image fetches per client per minute | 600 |

Tokens refill continuously rather than resetting on the minute, so a client that paces itself is
never blocked by a boundary.

Exceeding a budget returns `429` with a `Retry-After` header in seconds. Honour it; retrying
sooner consumes budget without succeeding.

Defaults are deployment-configurable — check with the service operator for your environment.

---

## 7. Worked example

Against a local development instance on `http://localhost:8080`.

```bash
# 1. Get a token.
ACCESS_TOKEN=$(curl -s \
  -u 'postalid-demo-client:dev-only-secret-change-me' \
  -d 'grant_type=client_credentials' \
  -d 'scope=postalid.verify postalid.images' \
  http://localhost:8080/oauth2/token | jq -r .access_token)

# 2. Verify a card.
curl -s -X POST http://localhost:8080/api/v1/verify \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
        "postalRefNo": "A11230000001",
        "firstName":   "JUAN",
        "middleName":  "SANTOS",
        "lastName":    "DELA CRUZ",
        "dateOfBirth": "1990-01-31"
      }' | tee /tmp/result.json | jq .

# 3. If VALID, fetch the images. Let curl name the file from the content type.
PHOTO=$(jq -r '.images.photo // empty' /tmp/result.json)
if [ -n "$PHOTO" ]; then
  curl -s "http://localhost:8080$PHOTO" \
    -H "Authorization: Bearer $ACCESS_TOKEN" -o photo.bin
  file photo.bin

  SIGNATURE=$(jq -r '.images.signature' /tmp/result.json)
  curl -s "http://localhost:8080$SIGNATURE" \
    -H "Authorization: Bearer $ACCESS_TOKEN" -o signature.png
fi
```

PowerShell equivalent for step 1:

```powershell
$pair  = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes(
           'postalid-demo-client:dev-only-secret-change-me'))
$token = (Invoke-RestMethod -Method Post -Uri 'http://localhost:8080/oauth2/token' `
           -Headers @{ Authorization = "Basic $pair" } `
           -Body @{ grant_type = 'client_credentials'
                    scope      = 'postalid.verify postalid.images' }).access_token
```

---

## 8. Client implementation notes

**Branch on `result`, not on the status code.** A non-matching card is `200 INVALID`.

**Cache the token, not for too long.** It lives five minutes. Re-use it across requests rather
than fetching one per call, but refresh on `401` rather than assuming a fixed lifetime.

**Fetch images promptly.** References expire in minutes. Do not persist them, and do not treat one
as a stable URL for a cardholder.

**Do not cache image bytes.** They are personal data and are served `no-store`. If you must hold
them, treat them with the same care as the card itself.

**Trust `Content-Type`.** Photo and signature come back in different formats.

**Check `validity.status` on every VALID.** A `VALID` result means the identity matched, not that
the card is current. An expired card returns `VALID` with `status: EXPIRED`.

**Treat `expiresOn` as indicative.** It is derived from the production date, not supplied by the
source system, and is flagged `derived: true` for that reason.

**Send only what you have.** Omit `middleName` when the card shows none, rather than sending an
empty string — though both are handled identically.

**Retry deliberately.** Retry `429` after `Retry-After`, and `5xx` with backoff. Never retry a
`400` or `403` unchanged; the call itself is wrong. Never retry an `INVALID` result — it is a
final answer, and hammering it will trip the rate limit and appear in the audit trail as
enumeration.

**Expect to be audited.** Every attempt is recorded against your client id, with a hashed
reference number, your source address and the outcome.
