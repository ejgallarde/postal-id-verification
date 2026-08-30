package com.postalid.verification.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.postalid.verification.domain.VerificationAudit;
import com.postalid.verification.domain.VerificationAuditRepository;
import com.postalid.verification.ingest.PacketIngestService;
import com.postalid.verification.testsupport.SyntheticPacket;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What a verification actually discloses.
 *
 * <p>Three separate boundaries are pinned here, because each is a decision a well-meaning change
 * could quietly erode: which cardholder fields reach the wire, that expiry is derived and labelled
 * as such, and that failure detail is reserved for clients specifically trusted with it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VerificationDisclosureTest {

    private static final String VERIFY_URL = "/api/v1/verify";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired VerificationAuditRepository auditRepository;

    @BeforeAll
    static void ingestPacket(@Autowired PacketIngestService ingestService) throws IOException {
        Path packetDir = Files.createTempDirectory("postalid-disclosure-test");
        SyntheticPacket.write(packetDir.resolve(SyntheticPacket.RECORD_ID));
        ingestService.ingestDirectory(packetDir);
    }

    private static RequestPostProcessor withScopes(String... scopes) {
        List<GrantedAuthority> authorities = Arrays.stream(scopes)
                .map(scope -> (GrantedAuthority) new SimpleGrantedAuthority("SCOPE_" + scope))
                .toList();
        return jwt().jwt(builder -> builder.claim("azp", "test-client")).authorities(authorities);
    }

    private String body(String first, String middle, String last, String dob, String prn) {
        String middleJson = (middle == null) ? "null" : ("\"" + middle + "\"");
        return "{"
                + "\"postalRefNo\":\"" + prn + "\","
                + "\"firstName\":\"" + first + "\","
                + "\"middleName\":" + middleJson + ","
                + "\"lastName\":\"" + last + "\","
                + "\"dateOfBirth\":\"" + dob + "\""
                + "}";
    }

    private String matchingBody() {
        return body(SyntheticPacket.FIRST_NAME, SyntheticPacket.MIDDLE_NAME,
                SyntheticPacket.LAST_NAME, SyntheticPacket.DATE_OF_BIRTH,
                SyntheticPacket.POSTAL_REF_NO);
    }

    // --- Cardholder details ---------------------------------------------------

    @Test
    @DisplayName("a VALID result returns the details printed on the card")
    void returnsCardPrintedDetails() throws Exception {
        mockMvc.perform(post(VERIFY_URL)
                        .with(withScopes("postalid.verify"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(matchingBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("VALID"))
                .andExpect(jsonPath("$.cardholder.postalRefNo").value(SyntheticPacket.POSTAL_REF_NO))
                .andExpect(jsonPath("$.cardholder.firstName").value(SyntheticPacket.FIRST_NAME))
                .andExpect(jsonPath("$.cardholder.middleName").value(SyntheticPacket.MIDDLE_NAME))
                .andExpect(jsonPath("$.cardholder.lastName").value(SyntheticPacket.LAST_NAME))
                .andExpect(jsonPath("$.cardholder.dateOfBirth").value(SyntheticPacket.DATE_OF_BIRTH))
                .andExpect(jsonPath("$.cardholder.gender").value("F"))
                .andExpect(jsonPath("$.cardholder.nationality").value("Filipino"))
                .andExpect(jsonPath("$.cardholder.address.barangay").value("BARANGAY UNO"))
                .andExpect(jsonPath("$.cardholder.address.city").value("Test City"));
    }

    @Test
    @DisplayName("fields the card does not print are never disclosed")
    void withholdsEverythingNotOnTheCard() throws Exception {
        MvcResult result = mockMvc.perform(post(VERIFY_URL)
                        .with(withScopes("postalid.verify"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(matchingBody()))
                .andExpect(jsonPath("$.result").value("VALID"))
                .andReturn();

        String raw = result.getResponse().getContentAsString();
        JsonNode cardholder = objectMapper.readTree(raw).get("cardholder");

        // All present on the enrollment record, none of them printed on the card. The government
        // identifiers and place of birth are the sharpest omissions: both are widely used as
        // knowledge-based authentication secrets, so disclosing them would enable impersonation.
        List<String> mustNotAppear = List.of(
                "mobileNo", "contactNo", "email", "tin", "crn", "gsis", "philhealth", "hdmf",
                "placeOfBirth", "pobCity", "motherLastName", "fatherLastName", "occupation",
                "companyName", "workCity", "orNumber", "orAmount", "recordId", "demoStatus",
                "capturedBy", "height", "weight");

        for (String field : mustNotAppear) {
            assertThat(cardholder.has(field))
                    .as("cardholder must not disclose %s", field)
                    .isFalse();
        }

        // Not merely absent as keys: the values must not appear anywhere in the response.
        assertThat(raw)
                .doesNotContain("09170000001")
                .doesNotContain("Test Birth City")
                .doesNotContain(SyntheticPacket.RECORD_ID);
    }

    // --- Validity -------------------------------------------------------------

    @Test
    @DisplayName("validity is derived from the card production date and flagged as derived")
    void returnsDerivedValidityWindow() throws Exception {
        mockMvc.perform(post(VERIFY_URL)
                        .with(withScopes("postalid.verify"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(matchingBody()))
                .andExpect(jsonPath("$.validity.status").value("ACTIVE"))
                // The fixture status trail records production on 2026-01-01.
                .andExpect(jsonPath("$.validity.issuedOn").value("2026-01-01"))
                .andExpect(jsonPath("$.validity.expiresOn").value("2029-01-01"))
                .andExpect(jsonPath("$.validity.daysUntilExpiry").isNumber())
                // Never silently presented as fact: the source data carries no expiry column.
                .andExpect(jsonPath("$.validity.derived").value(true));
    }

    // --- Diagnostics ----------------------------------------------------------

    @Test
    @DisplayName("without the diagnostics scope, INVALID says nothing about why")
    void hidesReasonByDefault() throws Exception {
        mockMvc.perform(post(VERIFY_URL)
                        .with(withScopes("postalid.verify"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("WRONGNAME", SyntheticPacket.MIDDLE_NAME,
                                SyntheticPacket.LAST_NAME, SyntheticPacket.DATE_OF_BIRTH,
                                SyntheticPacket.POSTAL_REF_NO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("INVALID"))
                .andExpect(jsonPath("$.reason").doesNotExist());
    }

    @Test
    @DisplayName("with the diagnostics scope, every mismatched field is named")
    void namesEveryMismatchedField() throws Exception {
        mockMvc.perform(post(VERIFY_URL)
                        .with(withScopes("postalid.verify", "postalid.diagnostics"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("WRONGNAME", SyntheticPacket.MIDDLE_NAME,
                                "ALSOWRONG", "1970-01-01", SyntheticPacket.POSTAL_REF_NO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("INVALID"))
                .andExpect(jsonPath("$.reason.code").value("FIELD_MISMATCH"))
                .andExpect(jsonPath("$.reason.mismatchedFields",
                        Matchers.containsInAnyOrder("lastName", "firstName", "dateOfBirth")))
                // A failure still discloses no cardholder details and no images.
                .andExpect(jsonPath("$.cardholder").doesNotExist())
                .andExpect(jsonPath("$.validity").doesNotExist())
                .andExpect(jsonPath("$.images").doesNotExist());
    }

    @Test
    @DisplayName("an unknown reference number is reported by code, naming no field")
    void reportsUnknownRecordByCode() throws Exception {
        mockMvc.perform(post(VERIFY_URL)
                        .with(withScopes("postalid.verify", "postalid.diagnostics"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(SyntheticPacket.FIRST_NAME, SyntheticPacket.MIDDLE_NAME,
                                SyntheticPacket.LAST_NAME, SyntheticPacket.DATE_OF_BIRTH,
                                "ZZ777777777Z")))
                .andExpect(jsonPath("$.reason.code").value("NO_SUCH_RECORD"))
                .andExpect(jsonPath("$.reason.mismatchedFields").doesNotExist());
    }

    // --- Audit ----------------------------------------------------------------

    @Test
    @DisplayName("the audit records every mismatched field even when the caller is told nothing")
    void auditRecordsAllMismatchesRegardlessOfScope() throws Exception {
        mockMvc.perform(post(VERIFY_URL)
                        .with(withScopes("postalid.verify"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("WRONGNAME", SyntheticPacket.MIDDLE_NAME,
                                "ALSOWRONG", SyntheticPacket.DATE_OF_BIRTH,
                                SyntheticPacket.POSTAL_REF_NO)))
                .andExpect(jsonPath("$.reason").doesNotExist());

        List<VerificationAudit> audits = auditRepository.findAll();
        VerificationAudit latest = audits.get(audits.size() - 1);

        assertThat(latest.getResult()).isEqualTo("INVALID");
        assertThat(latest.getMismatchedFields())
                .as("the audit keeps what the response withholds")
                .contains("lastName")
                .contains("firstName");
    }
}
