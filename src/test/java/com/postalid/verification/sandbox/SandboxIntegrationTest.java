package com.postalid.verification.sandbox;

import com.postalid.verification.domain.Cardholder;
import com.postalid.verification.domain.CardholderDocument;
import com.postalid.verification.domain.CardholderDocumentRepository;
import com.postalid.verification.domain.CardholderRepository;
import com.postalid.verification.domain.StorageKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The sandbox as a tester meets it: every scenario in the published catalogue answers the way the
 * catalogue says it will.
 *
 * <p>Runs the real seeder against its own in-memory database. The {@code test} profile is listed
 * last so its settings win where the two overlap, and the secrets the sandbox profile insists on
 * are supplied as properties, standing in for the environment variables a deployment would set.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:postalid-sandbox-test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "postalid.sandbox.packet-dir=target/sandbox-test/packets",
        "postalid.storage.root=target/sandbox-test/blobstore",
        "POSTALID_AUDIT_PRN_HASH_PEPPER=sandbox-test-pepper-not-a-secret",
        "SANDBOX_TESTER_SECRET=sandbox-test-secret",
        "SANDBOX_ADMIN_PASSWORD=sandbox-test-password",
        "SANDBOX_TESTER_CONSOLE_PASSWORD=sandbox-test-console-password"
})
@AutoConfigureMockMvc
@ActiveProfiles({"sandbox", "test"})
class SandboxIntegrationTest {

    private static final String VERIFY_URL = "/api/v1/verify";

    @Autowired MockMvc mockMvc;
    @Autowired CardholderRepository cardholderRepository;
    @Autowired CardholderDocumentRepository documentRepository;
    @Autowired SandboxCatalogue catalogue;

    private ResultActions verify(String prn, String first, String middle, String last, String dob,
                                 String query) throws Exception {
        String body = """
                {"postalRefNo":"%s","firstName":"%s","middleName":%s,"lastName":"%s","dateOfBirth":"%s"}
                """.formatted(prn, first, middle == null ? "null" : "\"" + middle + "\"", last, dob);

        return mockMvc.perform(post(VERIFY_URL + query)
                .with(jwt().jwt(builder -> builder.claim("azp", "sandbox-tester")).authorities(
                        new SimpleGrantedAuthority("SCOPE_postalid.verify"),
                        new SimpleGrantedAuthority("SCOPE_postalid.images"),
                        new SimpleGrantedAuthority("SCOPE_postalid.diagnostics")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions verify(String prn, String first, String middle, String last, String dob) throws Exception {
        return verify(prn, first, middle, last, dob, "");
    }

    @Test
    @DisplayName("every catalogue row is loaded, and nothing else")
    void loadsTheWholeCatalogue() throws Exception {
        int rows = catalogue.load().size();
        assertThat(rows).isEqualTo(17);
        assertThat(cardholderRepository.count()).isEqualTo(rows);
        assertThat(cardholderRepository.countByPostalRefNoNotIn(
                catalogue.load().stream().map(SandboxCardholder::postalRefNo).toList())).isZero();
    }

    @Test
    @DisplayName("each packet carries a real packet's full set of files, and only photo and signature are servable")
    void packetsMirrorARealDrop() throws Exception {
        Cardholder marigold = cardholderRepository.findByPostalRefNo("SBX000000001").orElseThrow();
        Map<String, CardholderDocument> documents = documentRepository.findByCardholderId(marigold.getId()).stream()
                .collect(Collectors.toMap(d -> d.getDocumentType().getCode(), d -> d));

        assertThat(documents.keySet()).containsExactlyInAnyOrder(
                "Photo", "Signature", "BC", "MC", "PC", "OR", "ORForm", "E6Form", "RT", "RI", "LT", "LI");

        // Copied into the blob store and servable: the two the API returns.
        assertThat(documents.get("Photo").getStorageKind()).isEqualTo(StorageKind.MANAGED);
        assertThat(documents.get("Signature").getStorageKind()).isEqualTo(StorageKind.MANAGED);
        // Recorded with a digest and a pointer, never copied, never servable.
        for (String code : List.of("BC", "MC", "PC", "OR", "ORForm", "E6Form", "RT", "RI", "LT", "LI")) {
            assertThat(documents.get(code).getStorageKind()).as(code).isEqualTo(StorageKind.EXTERNAL);
        }

        // The record without a signature has everything else.
        Cardholder paolo = cardholderRepository.findByPostalRefNo("SBX000000013").orElseThrow();
        assertThat(documentRepository.findByCardholderId(paolo.getId()))
                .extracting(d -> d.getDocumentType().getCode())
                .hasSize(11)
                .doesNotContain("Signature");
    }

    @ParameterizedTest(name = "{0} {1} {2} {3} -> VALID")
    @DisplayName("the named test cardholders verify, in the forms a tester is likely to type")
    @CsvSource(nullValues = "NULL", value = {
            "SBX000000001, MARIGOLD,  C,  LIBUIT-ARROYO, 1992-05-14",
            "SBX000000001, Marigold,  C., libuit-arroyo, 1992-05-14",
            "sbx000000001, marigold,  c,  Libuit-Arroyo, 1992-05-14",
            "SBX000000002, EARL JOHN, C,  GALLARDE,      1990-11-02",
            "SBX000000002, Earl John, C., Gallarde,      1990-11-02",
            "SBX000000003, MARIA,     R,  SANTOS,        1988-03-17",
            "SBX000000004, JUAN,      SANTOS, DELA CRUZ, 1990-01-31",
            "SBX000000005, JOSE ANTONIO, DE LOS REYES, PENAFLORIDA, 1985-07-22",
            "SBX000000005, José Antonio, de los Reyes, Peñaflorida, 1985-07-22",
            "SBX000000006, ANGELICA,  NULL, TAN,         1995-12-01",
            "SBX000000007, ROBERTO,   MANALO, VILLANUEVA, 1979-04-09",
            "SBX000000008, KRISTINE,  LOPEZ, DANGELO,    1997-06-18",
            "N41260423504, Nico,      Pariñas, Camerino, 1998-10-09",
            "N41260423504, NICO,      PARINAS, CAMERINO, 1998-10-09",
            "H19260421038, Rey Robert, Gentilizo, Castro, 1992-03-14",
            "E67180320259, Benjamin,  Coles, Perez,      1998-01-28",
            "E26250388757, Alexandrea, Bernas, Kabigting, 1995-11-06"
    })
    void namedCardholdersVerify(String prn, String first, String middle, String last, String dob) throws Exception {
        verify(prn, first, middle, last, dob)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("VALID"));
    }

    @Test
    @DisplayName("Marigold's VALID carries her card details and both images")
    void marigoldValidResponse() throws Exception {
        verify("SBX000000001", "MARIGOLD", "C", "LIBUIT-ARROYO", "1992-05-14")
                .andExpect(jsonPath("$.cardholder.lastName").value("LIBUIT-ARROYO"))
                .andExpect(jsonPath("$.cardholder.address.city").value("Quezon City"))
                .andExpect(jsonPath("$.validity.status").value("ACTIVE"))
                .andExpect(jsonPath("$.validity.issuedOn").value("2025-03-10"))
                .andExpect(jsonPath("$.images.photo").exists())
                .andExpect(jsonPath("$.images.signature").exists());
    }

    @Test
    @DisplayName("Earl John's VALID can carry its images inline")
    void earlJohnInlineImages() throws Exception {
        verify("SBX000000002", "EARL JOHN", "C", "GALLARDE", "1990-11-02", "?include=images")
                .andExpect(jsonPath("$.result").value("VALID"))
                .andExpect(jsonPath("$.imageData.photo.contentType").value("image/jpeg"))
                .andExpect(jsonPath("$.imageData.signature.contentType").value("image/png"))
                .andExpect(jsonPath("$.images").doesNotExist());
    }

    @ParameterizedTest(name = "surname \"{0}\" -> VALID")
    @DisplayName("surnames match with spaces ignored, so a space typed for the hyphen still verifies")
    @CsvSource({
            "SBX000000001, MARIGOLD, C,      LIBUIT ARROYO, 1992-05-14",
            "SBX000000001, MARIGOLD, C,      LIBUITARROYO,  1992-05-14",
            "SBX000000004, JUAN,     SANTOS, DELACRUZ,      1990-01-31",
            "SBX000000004, JUAN,     SANTOS, DE LA CRUZ,    1990-01-31"
    })
    void surnameSpacesIgnored(String prn, String first, String middle, String last, String dob) throws Exception {
        verify(prn, first, middle, last, dob)
                .andExpect(jsonPath("$.result").value("VALID"));
    }

    @Test
    @DisplayName("a full middle name does not match a record that holds only the initial")
    void fullMiddleNameAgainstStoredInitial() throws Exception {
        verify("SBX000000001", "MARIGOLD", "CRUZ", "LIBUIT-ARROYO", "1992-05-14")
                .andExpect(jsonPath("$.result").value("INVALID"))
                .andExpect(jsonPath("$.reason.mismatchedFields[0]").value("middleName"));
    }

    @Test
    @DisplayName("an expired card still verifies, and says it has expired")
    void expiredCard() throws Exception {
        verify("SBX000000009", "LUZVIMINDA", "GARCIA", "BAUTISTA", "1970-08-15")
                .andExpect(jsonPath("$.result").value("VALID"))
                .andExpect(jsonPath("$.validity.status").value("EXPIRED"))
                .andExpect(jsonPath("$.validity.expiresOn").value("2024-02-15"));
    }

    @Test
    @DisplayName("a record that never reached issue is INVALID, CARD_NOT_ISSUED")
    void notIssued() throws Exception {
        verify("SBX000000011", "RAMON", "AQUINO", "SORIANO", "1982-06-30")
                .andExpect(jsonPath("$.result").value("INVALID"))
                .andExpect(jsonPath("$.reason.code").value("CARD_NOT_ISSUED"));
    }

    @Test
    @DisplayName("a deleted record is INVALID, RECORD_DELETED")
    void deleted() throws Exception {
        verify("SBX000000012", "CECILIA", "MENDOZA", "RAMOS", "1975-10-10")
                .andExpect(jsonPath("$.result").value("INVALID"))
                .andExpect(jsonPath("$.reason.code").value("RECORD_DELETED"));
    }

    @Test
    @DisplayName("a record without a signature returns a photo reference only")
    void photoOnly() throws Exception {
        verify("SBX000000013", "PAOLO", "REYES", "FERNANDEZ", "1998-09-09")
                .andExpect(jsonPath("$.result").value("VALID"))
                .andExpect(jsonPath("$.images.photo").exists())
                .andExpect(jsonPath("$.images.signature").doesNotExist());
    }

    @Test
    @DisplayName("an unknown sandbox reference number is INVALID, NO_SUCH_RECORD")
    void unknownReference() throws Exception {
        verify("SBX999999999", "NOBODY", null, "ATALL", "1990-01-01")
                .andExpect(jsonPath("$.result").value("INVALID"))
                .andExpect(jsonPath("$.reason.code").value("NO_SUCH_RECORD"));
    }
}
