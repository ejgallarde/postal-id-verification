# PIVOT Sandbox

A PIVOT instance for outside parties to integrate and test against. It holds **only synthetic
cardholders**. No real person's enrollment record, photograph or signature is ever in it.

Part 1 is for testers. Part 2 is for whoever runs the sandbox.

---

## Part 1 — For testers

### What you get

- A client id and secret, sent to you separately from this document.
- The scopes `postalid.verify`, `postalid.images` and `postalid.diagnostics`. Diagnostics tells you
  *which* field failed on an `INVALID`, which production clients normally do not get. It is
  enabled here because every record is made up.
- The full API contract in [API.md](API.md), and a live copy at `/swagger-ui.html` on the sandbox.
- Optionally, a sign-in for the **web console** at the sandbox's root URL: username `tester` and a
  password sent separately. The console is what a PHLPost counter operator uses. Type in a card's
  details and it lays out the record, photo and signature in the positions they appear on the
  printed card.

### Quick start

```bash
BASE=https://<sandbox-host>

TOKEN=$(curl -s -u '<client-id>:<client-secret>' \
  -d grant_type=client_credentials \
  -d 'scope=postalid.verify postalid.images postalid.diagnostics' \
  $BASE/oauth2/token | jq -r .access_token)

curl -s -X POST "$BASE/api/v1/verify" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"postalRefNo":"SBX000000001","firstName":"MARIGOLD","middleName":"C",
       "lastName":"LIBUIT-ARROYO","dateOfBirth":"1992-05-14"}' | jq .

# The same, with the photo and signature in the response instead of as URLs:
curl -s -X POST "$BASE/api/v1/verify?include=images" ...
```

### Test cards

Most sandbox reference numbers start with `SBX`; the last four are expected values supplied by the
testing team. All are fictional. Each photo is a drawn card showing the person's
initials and a red **SANDBOX** band, so you can tell at a glance that the right image came back.

| Reference no. | First | Middle | Surname | Date of birth | Result | What it tests |
|---|---|---|---|---|---|---|
| `SBX000000001` | MARIGOLD | C | LIBUIT-ARROYO | 1992-05-14 | VALID · ACTIVE | Hyphenated surname; middle name on record as an initial only |
| `SBX000000002` | EARL JOHN | C | GALLARDE | 1990-11-02 | VALID · ACTIVE | Two-word first name; middle name on record as an initial only |
| `SBX000000003` | MARIA | REYES | SANTOS | 1988-03-17 | VALID · ACTIVE | Baseline. Full middle name, so `R` also matches |
| `SBX000000004` | JUAN | SANTOS | DELA CRUZ | 1990-01-31 | VALID · ACTIVE | Two-word surname |
| `SBX000000005` | JOSÉ ANTONIO | DE LOS REYES | PEÑAFLORIDA | 1985-07-22 | VALID · ACTIVE | Accents and ñ; multi-word middle name |
| `SBX000000006` | ANGELICA | *(none)* | TAN | 1995-12-01 | VALID · ACTIVE | No middle name: omit it or send `null` |
| `SBX000000007` | ROBERTO | MANALO | VILLANUEVA | 1979-04-09 | VALID · ACTIVE | Suffix (JR.) on record; not part of matching |
| `SBX000000008` | KRISTINE | LOPEZ | D'ANGELO | 1997-06-18 | VALID · ACTIVE | Apostrophe in surname |
| `SBX000000009` | LUZVIMINDA | GARCIA | BAUTISTA | 1970-08-15 | VALID · **EXPIRED** | Expired card: identity matches, card is not current |
| `SBX000000010` | GRACE | OCAMPO | VELASCO | 1993-02-28 | VALID · ACTIVE until 2026-11-01, then EXPIRED | Card near its expiry date |
| `SBX000000011` | RAMON | AQUINO | SORIANO | 1982-06-30 | INVALID · `CARD_NOT_ISSUED` | Enrolled, but no card was ever produced |
| `SBX000000012` | CECILIA | MENDOZA | RAMOS | 1975-10-10 | INVALID · `RECORD_DELETED` | Record flagged deleted |
| `SBX000000013` | PAOLO | REYES | FERNANDEZ | 1998-09-09 | VALID · ACTIVE, photo only | No signature captured: `images.signature` is absent |
| `N41260423504` | NICO | PARIÑAS | CAMERINO | 1998-10-09 | VALID · ACTIVE | ñ in the middle name; `PARINAS` also matches |
| `H19260421038` | REY ROBERT | GENTILIZO | CASTRO | 1992-03-14 | VALID · ACTIVE | Two-word first name |
| `E67180320259` | BENJAMIN | COLES | PEREZ | 1998-01-28 | VALID · ACTIVE | Tester-supplied expected data |
| `E26250388757` | ALEXANDREA | BERNAS | KABIGTING | 1995-11-06 | VALID · ACTIVE | Tester-supplied expected data |

Any number not listed returns `INVALID · NO_SUCH_RECORD`.

### Inputs worth trying

| Try | Expect | Why |
|---|---|---|
| `libuit-arroyo`, `Marigold`, `c.` | VALID | Case, surrounding spaces and `.` `-` `'` `,` are ignored |
| `José Antonio` / `Peñaflorida` **or** `JOSE ANTONIO` / `PENAFLORIDA` | VALID | Accents are removed before comparing |
| `D'ANGELO` **or** `DANGELO` | VALID | Apostrophes are removed |
| `LIBUIT ARROYO`, `LIBUITARROYO` or `Libuit-Arroyo` | VALID | Surnames are compared with spaces ignored, and hyphens are removed |
| `DELACRUZ` or `DE LA CRUZ` for Juan | VALID | Same rule: compound surnames match written either way |
| `EARLJOHN` for Earl John | INVALID · `firstName` | Spaces still count in first names, where they separate two names |
| Marigold with middle name `CRUZ` | INVALID · `middleName` | Her record holds only the initial `C`; a full name cannot be checked against it |
| Maria with middle name `R` | VALID | A single letter matches a stored full middle name |
| Maria with middle name omitted | INVALID · `middleName` | Omitting a middle name the record has is rejected |
| Any card, wrong date of birth | INVALID · `dateOfBirth` | Exact date only |
| Wrong surname **and** wrong date | INVALID · both fields listed | Every mismatched field is reported, not just the first |
| `?include=image` (typo) | `400`, `errors.include` | Unknown include values are rejected, not ignored |

### Limits you will meet

| Limit | Sandbox value |
|---|---|
| Verifications per client / per address | 60 per minute each |
| Image fetches per client / per address | 120 per minute each |
| Token requests per address | 20 per minute |
| Failed token requests per client id | 100 per hour, then refused until it refills |
| Console sign-in attempts per address | 10 per minute |
| Failed console sign-ins per username | 20 per hour, then refused until it refills |
| Console verifications per user | 30 per minute |
| INVALIDs before you are paused | 20 net in 10 minutes, then a 5-minute pause |

Every `429` carries `Retry-After`. Cache your access token for its five-minute life rather than
requesting one per call. Running through the INVALID cases above in a burst will trip the
pause; space them out or mix in VALID checks of **different** cards. Repeating the same VALID card
does not reset the count.

### Ground rules

- Sandbox data is synthetic. Do not submit real people's details; every attempt is logged against
  your client id with your source address.
- Do not run load or security tests without arranging them first.
- Report anything surprising with the `requestId` from the response.

---

## Part 2 — Running the sandbox

### What the profile does

`--spring.profiles.active=sandbox` (see [application-sandbox.yml](../src/main/resources/application-sandbox.yml)):

- At startup, writes the catalogue in
  [sandbox/cardholders.csv](../src/main/resources/sandbox/cardholders.csv) out as ordinary
  enrollment packets under `./data/sandbox/packets`, then loads them through the normal ingest
  path. Testers therefore exercise the real parser, transcoder and blob store.
- Each packet has the same 15 files as a real drop, in the same formats:

  | File | Synthetic content |
  |---|---|
  | `_demographics.csv`, `__demostatusdtl.csv` | The catalogue row, in the export's column format |
  | `_Photo.jpg` (480×640) | Drawn card with the person's initials and a SANDBOX band. **Served** |
  | `_Signature.tiff` (792×200, bilevel LZW) | The name in italic script. **Served**, converted to PNG |
  | `_BC` `_MC` `_PC` `_OR` `_ORForm` `.tiff` (A4, colour LZW) | Plain page stamped SPECIMEN with the card details. Recorded, never served |
  | `_E6Form.tiff` (A4, bilevel LZW) | As above, black and white like the real form. Recorded, never served |
  | `_RT` `_RI` `_LT` `_LI` `.wsq` | Four-byte stand-ins. Recorded, never served |
  | `lateSub_<date>.txt` | Manifest listing the scans as `.jpg`, like the real one |

  The scans are deliberately unlike any official form, with no layout or seal, so they cannot
  serve as a template for a forgery. Open any packet folder to see them.
- Uses its own database (`./data/sandbox/postalid`) and blob store (`./data/sandbox/blobstore`).
- Listens on `127.0.0.1` only, with TLS left to the tunnel in front of it.
- Trusts `X-Forwarded-For` from loopback only, so each tester gets their own per-address budget.
- Opens Swagger UI without sign-in, and applies tighter rate limits than production.

### Safety guards

Each guard **stops startup**, rather than logging a warning:

1. **Foreign records.** If the database holds any record that the catalogue does not list, the
   sandbox refuses to start. Pointing it at a copy of real data by mistake cannot
   expose that data.
2. **Ingest profile.** `sandbox` and `ingest` cannot run together, because `ingest` loads real
   packets.
3. **Catalogue rows.** No reference number may appear twice. The guard trusts the catalogue, so
   **every row must be fictional**: never add a real cardholder's details to it.
4. **Missing secrets.** The hash pepper, the tester secret and both console passwords have no
   defaults, so startup fails until they are set.

### Run it and put it online (Windows, one command)

[scripts/sandbox.ps1](../scripts/sandbox.ps1) does the whole thing:

```powershell
winget install --id Cloudflare.cloudflared -e --scope user   # once
mvn -DskipTests package
powershell -ExecutionPolicy Bypass -File scripts\sandbox.ps1        # start
powershell -ExecutionPolicy Bypass -File scripts\sandbox.ps1 -Stop  # stop
```

1. **Secrets, first run only.** It generates every secret into
   `%USERPROFILE%\.pivot-sandbox\sandbox.env`, readable by your Windows account only. Later runs
   reuse them, so testers keep working across restarts. Delete the file to rotate everything.
2. **Tunnel.** It opens a Cloudflare *quick tunnel*, which needs no account, and reads the public
   `https://….trycloudflare.com` URL from it.
3. **Sandbox.** It starts the sandbox on `127.0.0.1` with that URL as the token issuer, waits for
   `Sandbox ready`, and prints the URLs.

Send testers the URL, and send the credentials from `sandbox.env` over a **different** channel:

| For | Give them |
|---|---|
| API | client id `sandbox-tester`, secret `SANDBOX_TESTER_SECRET` |
| Web console | username `tester`, password `SANDBOX_TESTER_CONSOLE_PASSWORD` |

`sandbox-admin` / `SANDBOX_ADMIN_PASSWORD` is yours. Keep it.

**Quick tunnel limits.** The URL **changes every time the tunnel restarts**: after a reboot, a
crash, or `-Stop`. There is also no uptime guarantee, and this machine has to stay on and awake.
That is fine for a few days of testing with one partner. For anything longer, set up a permanent
address (below).

### What is exposed, and how it is protected

Everything goes through the tunnel: API, token endpoint, docs **and the web console**.

| Surface | Protection |
|---|---|
| Transport | HTTPS at Cloudflare's edge; HSTS; the sandbox itself only listens on loopback |
| API | OAuth client credentials, 5-minute scoped tokens, per-client and per-address limits |
| Token endpoint | 20 requests/min per address; 100 failures/hour per client id, checked before the secret |
| Console sign-in | 10 attempts/min per address; 20 failures/hour per username, checked before the password; CSRF |
| Console session | Secure, HttpOnly, SameSite=Strict cookie; 30-minute timeout |
| Console use | 30 verifications/min per operator; the same INVALID throttle as the API |
| Everything else | H2 console off; actuator reduced to `/health`; strict CSP; every attempt audited with the caller's real address |
| Data | Synthetic only, enforced at startup |

Behind the tunnel, every request reaches the sandbox from 127.0.0.1. The sandbox trusts the
tunnel's `X-Forwarded-For` header, and only from loopback, so each caller is still recorded and
rate-limited by their real address.

### A permanent address

For a stable hostname such as `pivot-sandbox.<your-domain>`, use a *named* tunnel. This needs a
Cloudflare account with the domain on it, and a one-time browser sign-in:

```powershell
cloudflared tunnel login
cloudflared tunnel create pivot-sandbox
cloudflared tunnel route dns pivot-sandbox pivot-sandbox.example.ph
```

```yaml
# %USERPROFILE%\.cloudflared\config.yml
tunnel: pivot-sandbox
credentials-file: C:\Users\<you>\.cloudflared\<tunnel-id>.json
ingress:
  - hostname: pivot-sandbox.example.ph
    service: http://127.0.0.1:8080
  - service: http_status:404
```

Then run `cloudflared tunnel run pivot-sandbox`, and start the jar with
`POSTALID_ISSUER=https://pivot-sandbox.example.ph`. A named tunnel can also run as a Windows
service (`cloudflared service install`), so it comes back after a reboot. It also unlocks
Cloudflare's own controls in front of the sandbox:

- **Access policies**, for example email one-time PINs for the console, so only invited people
  reach the sign-in page at all.
- **Service tokens**, as a second credential for API testers.
- **WAF rate-limiting rules**, which stop floods before they reach this machine.

To keep the console off the internet, add `path: ^/(api/|oauth2/|\.well-known/|v3/api-docs|swagger-ui)`
to the first ingress rule.

### Running it by hand

Without the script, set the same variables yourself and run the jar:

```bash
export POSTALID_AUDIT_PRN_HASH_PEPPER="$(openssl rand -base64 32)"
export SANDBOX_TESTER_SECRET="$(openssl rand -base64 32)"
export SANDBOX_TESTER_CONSOLE_PASSWORD="$(openssl rand -base64 18)"
export SANDBOX_ADMIN_PASSWORD="$(openssl rand -base64 24)"
export POSTALID_ISSUER=https://pivot-sandbox.example.ph    # the public URL testers call
export POSTALID_JWT_KEYSTORE=file:/secure/jwt-keystore.p12  # optional; see below
export POSTALID_JWT_KEYSTORE_PASSWORD=...

java -Duser.timezone=UTC -jar target/postal-id-verification-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=sandbox
```

Without a JWT keystore an ephemeral key is generated, and every outstanding token stops working on
restart. Tokens only last five minutes, so that is tolerable for a sandbox, but a keystore
avoids it.

The console's session cookie is secure-only, so sign in through the HTTPS URL, not
`http://127.0.0.1`. For a sandbox that is never exposed and is used locally over plain HTTP, set
`POSTALID_SECURE_COOKIE=false`.

Startup logs `Sandbox ready: 17 synthetic cardholder(s) loaded`. Until then, verification
returns `503` with `Retry-After`.

### Tester accounts

Give each organisation its own client. Add an entry under `postalid.oauth.clients` with its own
environment variable, for example in an `application-sandbox-local.yml` kept outside the
repository:

```yaml
postalid:
  oauth:
    clients:
      partner-acme:
        name: ACME Corp (sandbox)
        secret: ${SANDBOX_PARTNER_ACME_SECRET}
        scopes: [postalid.verify, postalid.images, postalid.diagnostics]
```

**Revoking access:** removing a client from configuration does **not** delete its row in
`oauth2_registered_client`. Either restart with a new random secret for it, which cuts it off at
once, or delete the row:

```sql
DELETE FROM oauth2_registered_client WHERE client_id = 'partner-acme';
```

### Changing the test data

Edit [sandbox/cardholders.csv](../src/main/resources/sandbox/cardholders.csv), rebuild and
restart. Seeding updates existing records in place. A row **removed** from the catalogue stays in
the database until you delete `./data/sandbox/` and restart, which rebuilds everything from the
catalogue.

Two rows use names given for testing: Marigold C. Libuit-Arroyo and Earl John C. Gallarde. Their
dates of birth, addresses and every other detail are made up. If either is a real person, they
should have agreed to their name appearing in a dataset shared with outside testers. For both, the
middle name is stored as the initial `C`; put the full middle name in the CSV if the full-name case
should be testable too.

### Watch it

- `verification_audit`: every attempt, by client and address.
- The log line `Throttling caller ... after N net INVALID results` marks an enumeration pause.
- The log line `Client id '...' has exhausted its failed-authentication budget` marks secret
  guessing, or a tester with a stale secret.
