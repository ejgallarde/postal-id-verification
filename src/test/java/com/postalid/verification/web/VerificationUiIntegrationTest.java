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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VerificationUiIntegrationTest {

    @Autowired MockMvc mockMvc;

    @BeforeAll
    static void ingestPacket(@Autowired PacketIngestService ingestService) throws IOException {
        Path packetDir = Files.createTempDirectory("postalid-ui-test");
        // Deliberately flat, unlike the API test's nested layout: both shapes must ingest.
        SyntheticPacket.write(packetDir);
        ingestService.ingestDirectory(packetDir);
    }

    @Test
    @DisplayName("the console redirects an anonymous visitor to the login page")
    void requiresSignIn() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("/login")));
    }

    @Test
    void loginPageIsPublic() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(view().name("login"));
    }

    @Test
    @WithMockUser(username = "test-operator", roles = "OPERATOR")
    void showsTheVerificationForm() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("verify"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("ID number")));
    }

    @Test
    @DisplayName("a form post without a CSRF token is rejected")
    void requiresCsrfToken() throws Exception {
        mockMvc.perform(post("/verify")
                        .param("postalRefNo", SyntheticPacket.POSTAL_REF_NO)
                        .param("firstName", SyntheticPacket.FIRST_NAME)
                        .param("lastName", SyntheticPacket.LAST_NAME)
                        .param("dateOfBirth", SyntheticPacket.DATE_OF_BIRTH))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "test-operator", roles = "OPERATOR")
    @DisplayName("a matching submission renders VALID with both image references")
    void rendersValidResult() throws Exception {
        mockMvc.perform(post("/verify").with(csrf())
                        .param("postalRefNo", SyntheticPacket.POSTAL_REF_NO)
                        .param("firstName", SyntheticPacket.FIRST_NAME)
                        .param("middleName", SyntheticPacket.MIDDLE_NAME)
                        .param("lastName", SyntheticPacket.LAST_NAME)
                        .param("dateOfBirth", SyntheticPacket.DATE_OF_BIRTH))
                .andExpect(status().isOk())
                .andExpect(view().name("result"))
                .andExpect(model().attribute("valid", true))
                .andExpect(model().attributeExists("photoRef", "signatureRef"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("VALID")));
    }

    @Test
    @WithMockUser(username = "test-operator", roles = "OPERATOR")
    @DisplayName("an INVALID page names no field and offers no images")
    void invalidPageRevealsNothing() throws Exception {
        MvcResult result = mockMvc.perform(post("/verify").with(csrf())
                        .param("postalRefNo", SyntheticPacket.POSTAL_REF_NO)
                        .param("firstName", "WRONGNAME")
                        .param("middleName", SyntheticPacket.MIDDLE_NAME)
                        .param("lastName", SyntheticPacket.LAST_NAME)
                        .param("dateOfBirth", SyntheticPacket.DATE_OF_BIRTH))
                .andExpect(status().isOk())
                .andExpect(model().attribute("valid", false))
                .andExpect(model().attribute("photoRef", org.hamcrest.Matchers.nullValue()))
                .andExpect(model().attribute("signatureRef", org.hamcrest.Matchers.nullValue()))
                .andReturn();

        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("INVALID");
        // The page must not hint at which field was wrong, nor echo the stored values.
        assertThat(html).doesNotContain("first name does not match")
                .doesNotContain(SyntheticPacket.LAST_NAME)
                .doesNotContain("FIRST_NAME_MISMATCH");
    }

    @Test
    @WithMockUser(username = "test-operator", roles = "OPERATOR")
    void redisplaysTheFormWhenInputIsInvalid() throws Exception {
        mockMvc.perform(post("/verify").with(csrf())
                        .param("postalRefNo", "!!")
                        .param("firstName", "")
                        .param("lastName", SyntheticPacket.LAST_NAME)
                        .param("dateOfBirth", SyntheticPacket.DATE_OF_BIRTH))
                .andExpect(status().isOk())
                .andExpect(view().name("verify"))
                .andExpect(model().attributeHasFieldErrors("form", "postalRefNo", "firstName"));
    }

    @Test
    @WithMockUser(username = "test-operator", roles = "OPERATOR")
    @DisplayName("the console serves images through its own session-authenticated route")
    void servesImagesToSignedInOperator() throws Exception {
        MvcResult verification = mockMvc.perform(post("/verify").with(csrf())
                        .param("postalRefNo", SyntheticPacket.POSTAL_REF_NO)
                        .param("firstName", SyntheticPacket.FIRST_NAME)
                        .param("middleName", SyntheticPacket.MIDDLE_NAME)
                        .param("lastName", SyntheticPacket.LAST_NAME)
                        .param("dateOfBirth", SyntheticPacket.DATE_OF_BIRTH))
                .andReturn();

        String photoRef = (String) verification.getModelAndView().getModel().get("photoRef");

        mockMvc.perform(get("/ui/images/" + photoRef))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    @WithMockUser(username = "another-operator", roles = "OPERATOR")
    @DisplayName("one operator cannot open the images another operator's verification produced")
    void imageReferenceIsBoundToTheOperator() throws Exception {
        MvcResult verification = mockMvc.perform(post("/verify").with(csrf())
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.user("test-operator").roles("OPERATOR"))
                        .param("postalRefNo", SyntheticPacket.POSTAL_REF_NO)
                        .param("firstName", SyntheticPacket.FIRST_NAME)
                        .param("middleName", SyntheticPacket.MIDDLE_NAME)
                        .param("lastName", SyntheticPacket.LAST_NAME)
                        .param("dateOfBirth", SyntheticPacket.DATE_OF_BIRTH))
                .andReturn();

        String photoRef = (String) verification.getModelAndView().getModel().get("photoRef");

        // The class-level user here is another-operator.
        mockMvc.perform(get("/ui/images/" + photoRef))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "test-operator", roles = "OPERATOR")
    @DisplayName("the console applies the same character allow-list to names as the API")
    void rejectsMarkupInNames() throws Exception {
        mockMvc.perform(post("/verify").with(csrf())
                        .param("postalRefNo", SyntheticPacket.POSTAL_REF_NO)
                        .param("firstName", "<script>")
                        .param("lastName", "SANTOS1")
                        .param("dateOfBirth", SyntheticPacket.DATE_OF_BIRTH))
                .andExpect(status().isOk())
                .andExpect(view().name("verify"))
                .andExpect(model().attributeHasFieldErrors("form", "firstName", "lastName"));
    }

    @Test
    @DisplayName("the theme switch loads before sign-in, from this origin, without loosening the CSP")
    void themeSwitchWorksUnderTheCsp() throws Exception {
        mockMvc.perform(get("/js/theme.js"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("src=\"/js/theme.js\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-theme-toggle")))
                // Same-origin script only: no inline script, so no 'unsafe-inline' needed.
                .andExpect(header().string("Content-Security-Policy",
                        org.hamcrest.Matchers.containsString("script-src 'self';")))
                .andExpect(header().string("Content-Security-Policy",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("unsafe-inline"))));
    }

    @Test
    @DisplayName("the console image route is not open to anonymous callers")
    void imageRouteRequiresSignIn() throws Exception {
        mockMvc.perform(get("/ui/images/anything"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @WithMockUser(username = "test-operator", roles = "OPERATOR")
    @DisplayName("the security headers that matter are present on rendered pages")
    void sendsSecurityHeaders() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Content-Security-Policy",
                        org.hamcrest.Matchers.containsString("default-src 'self'")));
    }
}
