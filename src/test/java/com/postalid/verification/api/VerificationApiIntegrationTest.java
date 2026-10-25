package com.postalid.verification.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.postalid.verification.domain.Cardholder;
import com.postalid.verification.domain.CardholderRepository;
import com.postalid.verification.domain.VerificationAudit;
import com.postalid.verification.domain.VerificationAuditRepository;
import com.postalid.verification.ingest.PacketIngestService;
import com.postalid.verification.testsupport.SyntheticPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end behaviour of the API surface: the token handshake, the verification contract, the
 * image flow, and the refusals that keep the endpoint from being usable as a data source.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VerificationApiIntegrationTest {

    private static final String VERIFY_URL = "/api/v1/verify";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired PacketIngestService ingestService;
    @Autowired CardholderRepository cardholderRepository;
    @Autowired VerificationAuditRepository auditRepository;

    private static Path packetDir;

    @BeforeAll
    static void writePacket(@Autowired PacketIngestService ingestService) throws IOException {
        packetDir = Files.createTempDirectory("postalid-api-test");
        // The real layout: one subfolder per enrollee, named for the record id.
        SyntheticPacket.write(packetDir.resolve(SyntheticPacket.RECORD_ID));
        ingestService.ingestDirectory(packetDir);
    }

    private String requestBody(String first, String middle, String last, String dob, String prn) {
        return """
                {"postalRefNo":"%s","firstName":"%s","middleName":%s,"lastName":"%s","dateOfBirth":"%s"}
                """.formatted(prn, first, middle == null ? "null" : "\"" + middle + "\"", last, dob);
    }

    private String validBody() {
        return requestBody(SyntheticPacket.FIRST_NAME, SyntheticPacket.MIDDLE_NAME,
                SyntheticPacket.LAST_NAME, SyntheticPacket.DATE_OF_BIRTH, SyntheticPacket.POSTAL_REF_NO);
    }

    /** A validated token carrying exactly the given scopes, without running the handshake. */
    private static org.springframework.test.web.servlet.request.RequestPostProcessor withScopes(String... scopes) {
        List<GrantedAuthority> authorities = java.util.Arrays.stream(scopes)
                .map(scope -> (GrantedAuthority) new SimpleGrantedAuthority("SCOPE_" + scope))
                .toList();

        return jwt().jwt(builder -> builder.claim("azp", "test-client")).authorities(authorities);
    }

    // --- The token handshake --------------------------------------------------

    @Test
    @DisplayName("a registered client can obtain a scoped access token")
    void issuesAccessTokenForClientCredentials() throws Exception {
        MvcResult result = mockMvc.perform(post("/oauth2/token")
                        .with(httpBasic("test-client", "test-client-secret"))
                        .param("grant_type", "client_credentials")
                        .param("scope", "postalid.verify postalid.images"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("access_token").asText()).isNotBlank();
        assertThat(body.get("token_type").asText()).isEqualToIgnoringCase("Bearer");
        assertThat(body.get("expires_in").asLong()).isPositive();
    }

    @Test
    void rejectsAnIncorrectClientSecret() throws Exception {
        mockMvc.perform(post("/oauth2/token")
                        .with(httpBasic("test-client", "wrong-secret"))
                        .param("grant_type", "client_credentials")
                        .param("scope", "postalid.verify"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a client cannot obtain a scope it was not granted")
    void refusesUngrantedScope() throws Exception {
        mockMvc.perform(post("/oauth2/token")
                        .with(httpBasic("verify-only-client", "verify-only-secret"))
                        .param("grant_type", "client_credentials")
                        .param("scope", "postalid.images"))
                .andExpect(status().isBadRequest());
    }

    // --- Access control -------------------------------------------------------

    @Test
    void rejectsUnauthenticatedVerification() throws Exception {
        mockMvc.perform(post(VERIFY_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the images scope alone does not grant verification")
    void requiresVerifyScopeToVerify() throws Exception {
        mockMvc.perform(post(VERIFY_URL)
                        .with(withScopes("postalid.images"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the verify scope alone does not grant image retrieval")
    void requiresImagesScopeToFetchImages() throws Exception {
        String photoRef = photoReferenceFromValidVerification();

        mockMvc.perform(get("/api/v1/images/" + photoRef).with(withScopes("postalid.verify")))
                .andExpect(status().isForbidden());
    }

    // --- The verification contract -------------------------------------------

    @Test
    @DisplayName("matching details return VALID with image references")
    void validSubmissionReturnsImages() throws Exception {
        mockMvc.perform(post(VERIFY_URL)
                        .with(withScopes("postalid.verify", "postalid.images"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.result").value("VALID"))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(jsonPath("$.images.photo").isNotEmpty())
                .andExpect(jsonPath("$.images.signature").isNotEmpty())
                .andExpect(jsonPath("$.imagesExpireIn").value(300));
    }

    @Test
    @DisplayName("include=images embeds the photo and signature and issues no URLs")
    void includeImagesEmbedsThem() throws Exception {
        MvcResult result = mockMvc.perform(post(VERIFY_URL + "?include=images")
                        .with(withScopes("postalid.verify", "postalid.images"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("VALID"))
                .andExpect(jsonPath("$.imageData.photo.contentType").value("image/jpeg"))
                .andExpect(jsonPath("$.imageData.signature.contentType").value("image/png"))
                // Nothing left to fetch, so no URLs and no expiry for them.
                .andExpect(jsonPath("$.images").doesNotExist())
                .andExpect(jsonPath("$.imagesExpireIn").doesNotExist())
                .andReturn();

        JsonNode photo = new ObjectMapper().readTree(result.getResponse().getContentAsString())
                .get("imageData").get("photo");
        byte[] bytes = java.util.Base64.getDecoder().decode(photo.get("data").asText());
        assertThat(bytes.length).isEqualTo(photo.get("byteLength").asInt());
        // JPEG start-of-image marker: the bytes really are the photograph, not a placeholder.
        assertThat(bytes[0]).isEqualTo((byte) 0xFF);
        assertThat(bytes[1]).isEqualTo((byte) 0xD8);
    }

    @Test
    @DisplayName("include=images without the images scope is refused before anything is verified")
    void includeImagesRequiresTheImagesScope() throws Exception {
        mockMvc.perform(post(VERIFY_URL + "?include=images")
                        .with(withScopes("postalid.verify"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an unknown include value is a 400 naming the parameter, not silently ignored")
    void unknownIncludeValueIsRejected() throws Exception {
        mockMvc.perform(post(VERIFY_URL + "?include=image")
                        .with(withScopes("postalid.verify", "postalid.images"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.include").value("Supported values: images"));
    }

    @Test
    @DisplayName("include=images on an INVALID result embeds nothing")
    void includeImagesOnInvalidEmbedsNothing() throws Exception {
        mockMvc.perform(post(VERIFY_URL + "?include=images")
                        .with(withScopes("postalid.verify", "postalid.images"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("WRONG", SyntheticPacket.MIDDLE_NAME, SyntheticPacket.LAST_NAME,
                                SyntheticPacket.DATE_OF_BIRTH, SyntheticPacket.POSTAL_REF_NO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("INVALID"))
                .andExpect(jsonPath("$.imageData").doesNotExist());
    }

    @Test
    @DisplayName("a middle initial is accepted in place of the full middle name")
    void acceptsMiddleInitial() throws Exception {
        mockMvc.perform(post(VERIFY_URL)
                        .with(withScopes("postalid.verify"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(SyntheticPacket.FIRST_NAME, "R", SyntheticPacket.LAST_NAME,
                                SyntheticPacket.DATE_OF_BIRTH, SyntheticPacket.POSTAL_REF_NO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("VALID"));
    }

    @Test
    @DisplayName("every failing case returns an identical INVALID body with no images")
    void invalidResponsesAreIndistinguishable() throws Exception {
        List<String> failingBodies = List.of(
                // Unknown reference number.
                requestBody(SyntheticPacket.FIRST_NAME, SyntheticPacket.MIDDLE_NAME,
                        SyntheticPacket.LAST_NAME, SyntheticPacket.DATE_OF_BIRTH, "ZZ999999999Z"),
                // Wrong surname.
                requestBody(SyntheticPacket.FIRST_NAME, SyntheticPacket.MIDDLE_NAME,
                        "WRONGNAME", SyntheticPacket.DATE_OF_BIRTH, SyntheticPacket.POSTAL_REF_NO),
                // Wrong first name.
                requestBody("WRONGNAME", SyntheticPacket.MIDDLE_NAME,
                        SyntheticPacket.LAST_NAME, SyntheticPacket.DATE_OF_BIRTH, SyntheticPacket.POSTAL_REF_NO),
                // Wrong middle name.
                requestBody(SyntheticPacket.FIRST_NAME, "WRONGNAME",
                        SyntheticPacket.LAST_NAME, SyntheticPacket.DATE_OF_BIRTH, SyntheticPacket.POSTAL_REF_NO),
                // Wrong date of birth.
                requestBody(SyntheticPacket.FIRST_NAME, SyntheticPacket.MIDDLE_NAME,
                        SyntheticPacket.LAST_NAME, "1990-12-25", SyntheticPacket.POSTAL_REF_NO));

        for (String body : failingBodies) {
            MvcResult result = mockMvc.perform(post(VERIFY_URL)
                            .with(withScopes("postalid.verify", "postalid.images"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result").value("INVALID"))
                    .andReturn();

            JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());

            // The only keys present are the ones that carry no information about the record.
            assertThat(json.fieldNames()).toIterable()
                    .containsExactlyInAnyOrder("result", "verifiedAt", "requestId");
            assertThat(json.has("images")).isFalse();
            assertThat(json.has("reason")).isFalse();
            assertThat(json.has("cardholder")).isFalse();
            assertThat(json.has("validity")).isFalse();
        }
    }

    @Test
    @DisplayName("a record that never reached an issued status does not verify")
    void refusesRecordThatWasNeverIssued() throws Exception {
        Cardholder cardholder = cardholderRepository
                .findByPostalRefNo(SyntheticPacket.POSTAL_REF_NO).orElseThrow();
        String originalStatus = cardholder.getDemoStatus();
        cardholder.setDemoStatus("FCD");
        cardholderRepository.saveAndFlush(cardholder);

        try {
            mockMvc.perform(post(VERIFY_URL)
                            .with(withScopes("postalid.verify"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validBody()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result").value("INVALID"))
                    .andExpect(jsonPath("$.images").doesNotExist());
        } finally {
            cardholder.setDemoStatus(originalStatus);
            cardholderRepository.saveAndFlush(cardholder);
        }
    }

    @Test
    @DisplayName("a deleted record does not verify")
    void refusesDeletedRecord() throws Exception {
        Cardholder cardholder = cardholderRepository
                .findByPostalRefNo(SyntheticPacket.POSTAL_REF_NO).orElseThrow();
        cardholder.setDeleted(true);
        cardholderRepository.saveAndFlush(cardholder);

        try {
            mockMvc.perform(post(VERIFY_URL)
                            .with(withScopes("postalid.verify"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validBody()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result").value("INVALID"));
        } finally {
            cardholder.setDeleted(false);
            cardholderRepository.saveAndFlush(cardholder);
        }
    }

    // --- Validation -----------------------------------------------------------

    @Test
    @DisplayName("malformed input is a 400 problem document naming the offending fields")
    void rejectsMalformedInput() throws Exception {
        mockMvc.perform(post(VERIFY_URL)
                        .with(withScopes("postalid.verify"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"postalRefNo":"!!","firstName":"","lastName":"X","dateOfBirth":"2999-01-01"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors.postalRefNo").isNotEmpty())
                .andExpect(jsonPath("$.errors.firstName").isNotEmpty())
                .andExpect(jsonPath("$.errors.dateOfBirth").isNotEmpty());
    }

    @Test
    @DisplayName("a name carrying markup is rejected rather than quietly stripped")
    void rejectsInjectionAttemptInNames() throws Exception {
        mockMvc.perform(post(VERIFY_URL)
                        .with(withScopes("postalid.verify"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("<script>alert(1)</script>", null, "SANTOS",
                                SyntheticPacket.DATE_OF_BIRTH, SyntheticPacket.POSTAL_REF_NO)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.firstName").isNotEmpty());
    }

    @Test
    @DisplayName("a SQL metacharacter in the reference number fails validation, never reaching a query")
    void rejectsSqlMetacharactersInReferenceNumber() throws Exception {
        mockMvc.perform(post(VERIFY_URL)
                        .with(withScopes("postalid.verify"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(SyntheticPacket.FIRST_NAME, null, SyntheticPacket.LAST_NAME,
                                SyntheticPacket.DATE_OF_BIRTH, "' OR '1'='1")))
                .andExpect(status().isBadRequest());
    }

    // --- Images ---------------------------------------------------------------

    @Test
    @DisplayName("the photo is served as captured and the signature is converted to PNG")
    void servesPhotoAndTranscodedSignature() throws Exception {
        MvcResult verification = performValidVerification();
        JsonNode images = objectMapper.readTree(verification.getResponse().getContentAsString()).get("images");

        mockMvc.perform(get(images.get("photo").asText()).with(withScopes("postalid.images")))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andExpect(header().string("Cache-Control", "no-store"));

        mockMvc.perform(get(images.get("signature").asText()).with(withScopes("postalid.images")))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG));
    }

    @Test
    @DisplayName("an unknown image reference is a 404, not a server error")
    void unknownImageReferenceIsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/images/thisIsNotARealReferenceAtAll000000000000")
                        .with(withScopes("postalid.images")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("an image reference stops working once its uses are spent")
    void imageReferenceIsExhaustedAfterItsUseLimit() throws Exception {
        String photoRef = photoReferenceFromValidVerification();
        String url = "/api/v1/images/" + photoRef;

        // token-max-uses is 5 in the test profile.
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get(url).with(withScopes("postalid.images")))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(get(url).with(withScopes("postalid.images")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("an image reference works only for the client whose verification minted it")
    void imageReferenceIsBoundToTheMintingClient() throws Exception {
        String photoRef = photoReferenceFromValidVerification();

        // Another client, holding the images scope legitimately, gets the same 404 as for a
        // reference that never existed.
        mockMvc.perform(get("/api/v1/images/" + photoRef)
                        .with(jwt().jwt(builder -> builder.claim("azp", "some-other-client"))
                                .authorities(new SimpleGrantedAuthority("SCOPE_postalid.images"))))
                .andExpect(status().isNotFound());

        // And the attempt did not spend one of the owner's uses: all five are still there.
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/api/v1/images/" + photoRef).with(withScopes("postalid.images")))
                    .andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("an INVALID verification hands out no image reference at all")
    void invalidVerificationMintsNoReferences() throws Exception {
        MvcResult result = mockMvc.perform(post(VERIFY_URL)
                        .with(withScopes("postalid.verify", "postalid.images"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("WRONGNAME", SyntheticPacket.MIDDLE_NAME, SyntheticPacket.LAST_NAME,
                                SyntheticPacket.DATE_OF_BIRTH, SyntheticPacket.POSTAL_REF_NO)))
                .andReturn();

        assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).has("images")).isFalse();
    }

    @Test
    @DisplayName("only the photo and signature are ever referenced; the fingerprint never is")
    void neverMintsReferencesForFingerprints() throws Exception {
        MvcResult result = performValidVerification();
        JsonNode images = objectMapper.readTree(result.getResponse().getContentAsString()).get("images");

        assertThat(images.fieldNames()).toIterable().containsExactlyInAnyOrder("photo", "signature");
        assertThat(images.has("rt")).isFalse();
    }

    // --- Audit ----------------------------------------------------------------

    @Test
    @DisplayName("an attempt is audited with a hashed reference number and never the raw value")
    void auditsWithoutStoringTheReferenceNumber() throws Exception {
        performValidVerification();

        List<VerificationAudit> audits = auditRepository.findAll();
        assertThat(audits).isNotEmpty();

        VerificationAudit latest = audits.get(audits.size() - 1);
        assertThat(latest.getPrnHash()).hasSize(64).isNotEqualTo(SyntheticPacket.POSTAL_REF_NO);
        assertThat(latest.getChannel()).isEqualTo("API");
        assertThat(latest.getRequestId()).isNotBlank();

        assertThat(audits).allSatisfy(audit ->
                assertThat(audit.getPrnHash()).doesNotContain(SyntheticPacket.POSTAL_REF_NO));
    }

    @Test
    @DisplayName("the reason for an INVALID is recorded internally even though it is never returned")
    void recordsFailureReasonInTheAuditOnly() throws Exception {
        mockMvc.perform(post(VERIFY_URL)
                        .with(withScopes("postalid.verify"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(SyntheticPacket.FIRST_NAME, SyntheticPacket.MIDDLE_NAME,
                                SyntheticPacket.LAST_NAME, SyntheticPacket.DATE_OF_BIRTH, "ZZ888888888Z")))
                .andExpect(jsonPath("$.result").value("INVALID"));

        List<VerificationAudit> audits = auditRepository.findAll();
        VerificationAudit latest = audits.get(audits.size() - 1);

        assertThat(latest.getResult()).isEqualTo("INVALID");
        assertThat(latest.getFailureReasonCode()).isEqualTo("NO_SUCH_RECORD");
    }

    // --- Documentation --------------------------------------------------------

    @Test
    @WithMockUser(username = "test-operator", roles = "OPERATOR")
    @DisplayName("the OpenAPI document is generated and describes both endpoints")
    void publishesOpenApiDocument() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/verify'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/images/{reference}'].get").exists())
                .andExpect(jsonPath("$.components.securitySchemes['oauth2-client-credentials']").exists())
                .andExpect(jsonPath("$.components.securitySchemes['oauth2-client-credentials']"
                        + ".flows.clientCredentials.scopes['postalid.verify']").exists());
    }

    @Test
    @DisplayName("the API contract is not readable anonymously unless deliberately published")
    void apiDocsAreProtectedByDefault() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().is3xxRedirection());
    }

    // --- Helpers --------------------------------------------------------------

    private MvcResult performValidVerification() throws Exception {
        return mockMvc.perform(post(VERIFY_URL)
                        .with(withScopes("postalid.verify", "postalid.images"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(jsonPath("$.result").value("VALID"))
                .andReturn();
    }

    private String photoReferenceFromValidVerification() throws Exception {
        MvcResult result = performValidVerification();
        String photoUrl = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("images").get("photo").asText();
        return photoUrl.substring(photoUrl.lastIndexOf('/') + 1);
    }
}
