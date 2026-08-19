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
                                        "Retrieve the photo and signature of a verified cardholder"))));

        return new OpenAPI()
                .info(new Info()
                        .title("Postal ID Verification API")
                        .version("1.0.0")
                        .description("""
                                Validates Postal Identification cards against the enrollment database.

                                ## How a verification works
                                Submit the details printed on a card. The service answers `VALID` when
                                a matching record exists, has not been deleted, has reached an issued
                                status, and the name and date of birth agree. Otherwise it answers
                                `INVALID`.

                                ## What INVALID does not tell you
                                Every failing case returns the same body. The API will not say whether
                                a reference number exists or which field was wrong. This is deliberate:
                                a more helpful answer would let the endpoint be used to harvest
                                personal data belonging to other people.

                                ## Images
                                A `VALID` response includes short-lived references to the cardholder's
                                photograph and signature. They expire within minutes and permit only a
                                few fetches. Fingerprints and supporting document scans are held in the
                                database but are not retrievable through this API under any scope.

                                ## Rate limits
                                Requests are budgeted per client and per source address. Exceeding a
                                budget returns `429` with a `Retry-After` header.
                                """)
                        .contact(new Contact().name("Postal ID Verification Service"))
                        .license(new License().name("Internal use")))
                .servers(List.of(new Server()
                        .url(issuer == null || issuer.isBlank() ? "/" : issuer)
                        .description("Postal ID verification service")))
                .components(new Components()
                        .addSecuritySchemes("oauth2-client-credentials", clientCredentials))
                .addSecurityItem(new SecurityRequirement().addList("oauth2-client-credentials"));
    }
}
