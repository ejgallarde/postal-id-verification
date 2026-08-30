package com.postalid.verification.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.Scopes;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * The published API contract.
 *
 * <p>Describing the OAuth flow in the document itself means Swagger UI can obtain a token and
 * exercise the endpoints, rather than leaving every client author to work the handshake out from
 * prose.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI postalIdOpenApi(PostalIdProperties properties) {
        String issuer = properties.getOauth().getIssuer();
        String tokenUrl = (issuer == null || issuer.isBlank() ? "" : issuer) + "/oauth2/token";

        SecurityScheme clientCredentials = new SecurityScheme()
                .type(SecurityScheme.Type.OAUTH2)
                .description("""
                        OAuth 2.0 client credentials. Request a token from the token endpoint using
                        your client id and secret over HTTP Basic, then send it as a bearer token.
                        Tokens are short-lived; request a new one rather than caching indefinitely.
                        """)
                .flows(new OAuthFlows().clientCredentials(new OAuthFlow()
                        .tokenUrl(tokenUrl)
                        .scopes(new Scopes()
                                .addString("postalid.verify", "Submit Postal ID verification requests")
                                .addString("postalid.images",
                                        "Retrieve the photo and signature of a verified cardholder")
                                .addString("postalid.diagnostics",
                                        "Receive the reason and mismatched fields on an INVALID result. "
                                        + "Granted per client; without it every INVALID is identical."))));

        return new OpenAPI()
                .info(new Info()
                        .title("PIVOT — PHLPost Identity Verification API")
                        .version("1.0.0")
                        .description("""
                                PIVOT — the PHLPost Identity Verification & Onboarding Toolkit.

                                Validates Postal Identification cards against the enrollment database.

                                ## How a verification works
                                Submit the details printed on a card. The service answers `VALID` when
                                a matching record exists, has not been deleted, has reached an issued
                                status, and the name and date of birth agree. Otherwise it answers
                                `INVALID`.

                                ## What a VALID response carries
                                The cardholder details printed on the card, the card validity window,
                                and short-lived references to the photograph and signature.

                                Expiry is **derived**, not supplied by the source data: it is the card
                                production date plus a configured validity period, and is flagged
                                `derived: true`. An expired card still returns `VALID` with
                                `validity.status = EXPIRED`, because the identity did check out.
                                Read `validity.status` and apply your own policy.

                                ## What INVALID does not tell you
                                Every failing case returns the same body. The API will not say whether
                                a reference number exists or which field was wrong. This is deliberate:
                                a more helpful answer would let the endpoint be used to harvest
                                personal data belonging to other people.

                                Clients granted the `postalid.diagnostics` scope additionally receive
                                a `reason` object naming every mismatched field. That scope is granted
                                per client, not by default.

                                ## Images
                                A `VALID` response includes short-lived references to the cardholder's
                                photograph and signature. They expire within minutes and permit only a
                                few fetches. Fingerprints and supporting document scans are held in the
                                database but are not retrievable through this API under any scope.

                                ## Rate limits
                                Requests are budgeted per client and per source address. Exceeding a
                                budget returns `429` with a `Retry-After` header.
                                """)
                        .contact(new Contact().name("PIVOT — PHLPost Identity Verification & Onboarding Toolkit"))
                        .license(new License().name("Internal use")))
                .servers(List.of(new Server()
                        .url(issuer == null || issuer.isBlank() ? "/" : issuer)
                        .description("Postal ID verification service")))
                .components(new Components()
                        .addSecuritySchemes("oauth2-client-credentials", clientCredentials))
                .addSecurityItem(new SecurityRequirement().addList("oauth2-client-credentials"));
    }
}
