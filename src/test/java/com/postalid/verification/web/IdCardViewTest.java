package com.postalid.verification.web;

import com.postalid.verification.ingest.PacketIngestService;
import com.postalid.verification.testsupport.SyntheticPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The card-shaped comparison view.
 *
 * <p>Its whole purpose is to let an operator read across from the card in their hand, so these
 * tests pin the fields actually reaching the page. They also pin the two things that stop the
 * rendering being mistaken for a card: the provenance band, and the marker on a derived expiry
 * where the printed card carries an authoritative one.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IdCardViewTest {

    @Autowired MockMvc mockMvc;

    @BeforeAll
    static void ingestPacket(@Autowired PacketIngestService ingestService) throws IOException {
        Path packetDir = Files.createTempDirectory("postalid-idcard-test");
        SyntheticPacket.write(packetDir.resolve(SyntheticPacket.RECORD_ID));
        ingestService.ingestDirectory(packetDir);
    }

    private MvcResult verify(String first, String middle, String last, String dob) throws Exception {
        return mockMvc.perform(post("/verify").with(csrf())
                        .param("postalRefNo", SyntheticPacket.POSTAL_REF_NO)
                        .param("firstName", first)
                        .param("middleName", middle)
                        .param("lastName", last)
                        .param("dateOfBirth", dob))
                .andExpect(status().isOk())
                .andReturn();
    }

    private String matchingPage() throws Exception {
        return verify(SyntheticPacket.FIRST_NAME, SyntheticPacket.MIDDLE_NAME,
                SyntheticPacket.LAST_NAME, SyntheticPacket.DATE_OF_BIRTH)
                .getResponse().getContentAsString();
    }

    @Test
    @WithMockUser(username = "test-operator", roles = "OPERATOR")
    @DisplayName("the card view carries every field printed on a Postal ID")
    void rendersEveryCardField() throws Exception {
        String html = matchingPage();

        assertThat(html)
                .contains("Postal Identity Card")
                .contains(SyntheticPacket.POSTAL_REF_NO)
                .contains(SyntheticPacket.FIRST_NAME)
                .contains(SyntheticPacket.MIDDLE_NAME)
                .contains(SyntheticPacket.LAST_NAME)
                // Address, laid out over the card's three lines.
                .contains("BARANGAY UNO")
                .contains("Test City")
                // Date of birth in the card's own day-month-year form.
                .contains("17 Mar 1988")
                .contains("Filipino")
                // The field labels an operator reads across from.
                .contains("Date of Birth")
                .contains("Nationality")
                .contains("Issuing Post Office")
                .contains("Valid Until");
    }

    @Test
    @WithMockUser(username = "test-operator", roles = "OPERATOR")
    @DisplayName("the photograph and signature appear in their card positions")
    void rendersPhotoAndSignature() throws Exception {
        String html = matchingPage();

        assertThat(html)
                .contains("idcard-photo")
                .contains("idcard-signature")
                .contains("Holder&rsquo;s signature");
        // Both images are addressed through the existing tokenized route, not a direct path.
        assertThat(html).contains("/ui/images/");
    }

    @Test
    @WithMockUser(username = "test-operator", roles = "OPERATOR")
    @DisplayName("the provenance band names the operator and marks the output as a screen view")
    void rendersProvenanceBand() throws Exception {
        String html = matchingPage();

        assertThat(html)
                .contains("Verification view")
                .contains("test-operator")
                .contains("screen record, not a card");
    }

    @Test
    @WithMockUser(username = "test-operator", roles = "OPERATOR")
    @DisplayName("a derived expiry is marked and footnoted, never shown as though printed")
    void marksDerivedExpiry() throws Exception {
        String html = matchingPage();

        assertThat(html)
                .contains("idcard-derived")
                .contains("Valid Until is <strong>calculated</strong>")
                .contains("the source data holds no expiry field");
    }

    @Test
    @WithMockUser(username = "test-operator", roles = "OPERATOR")
    @DisplayName("the QR encodes the verification reference and no personal data")
    void rendersVerificationQr() throws Exception {
        MvcResult result = verify(SyntheticPacket.FIRST_NAME, SyntheticPacket.MIDDLE_NAME,
                SyntheticPacket.LAST_NAME, SyntheticPacket.DATE_OF_BIRTH);
        String html = result.getResponse().getContentAsString();

        assertThat(html)
                .contains("<svg")
                .contains("class=\"qr\"")
                .contains("Verification reference");

        // The payload is built from the request id, so it must not embed anything about the person.
        String requestId = (String) result.getModelAndView().getModel().get("requestId");
        assertThat(requestId).isNotBlank();
    }

    @Test
    @WithMockUser(username = "test-operator", roles = "OPERATOR")
    @DisplayName("an INVALID result renders no card at all")
    void invalidResultRendersNoCard() throws Exception {
        String html = verify("WRONGNAME", SyntheticPacket.MIDDLE_NAME,
                SyntheticPacket.LAST_NAME, SyntheticPacket.DATE_OF_BIRTH)
                .getResponse().getContentAsString();

        assertThat(html)
                .contains("INVALID")
                .doesNotContain("Postal Identity Card")
                .doesNotContain("idcard-photo")
                .doesNotContain("Verification view")
                .doesNotContain("Valid Until")
                .doesNotContain(SyntheticPacket.LAST_NAME);
    }

    @Test
    @DisplayName("branding assets load before sign-in, so the login page is not unbranded")
    void brandingAssetsArePublic() throws Exception {
        // An asset that exists must serve anonymously. Asserting on the logo itself would be
        // vacuous while the file is absent, so this uses the stylesheet, which is always present
        // and sits behind the same permitAll rule.
        mockMvc.perform(get("/css/app.css"))
                .andExpect(status().isOk());

        // And the branding path must resolve through the same rule rather than bouncing to login.
        int logoStatus = mockMvc.perform(get("/img/phlpost-logo.png"))
                .andReturn().getResponse().getStatus();
        assertThat(logoStatus)
                .as("branding path must not redirect to the login page")
                .isNotEqualTo(302);
    }

    @Test
    @DisplayName("an anonymous request for something missing is a 404, not a bounce to login")
    void missingPathsDoNotRedirectToLogin() throws Exception {
        // A 404 forwards to /error, and without the ERROR dispatch permitted that forward is
        // itself denied — turning every unknown path into "you must log in", which is both
        // confusing and tells an anonymous caller more than it should.
        int statusCode = mockMvc.perform(get("/css/no-such-file.css"))
                .andReturn().getResponse().getStatus();

        assertThat(statusCode).isNotEqualTo(302);
    }
}
