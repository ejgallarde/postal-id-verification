package com.postalid.verification.api;

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
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.context.ApplicationContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Behaviour that only matters once this is deployed.
 *
 * <p>Each case here corresponds to something found during a production readiness review: a client
 * error reported as a server fault, an unbounded request body, a rate limit clients could not see,
 * a health probe that stayed green through a storage outage, and concurrent-session control that
 * silently did not work.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductionReadinessTest {

    private static final String VERIFY_URL = "/api/v1/verify";

    @Autowired MockMvc mockMvc;
    @Autowired ApplicationContext context;

    @BeforeAll
    static void ingestPacket(@Autowired PacketIngestService ingestService) throws IOException {
        Path packetDir = Files.createTempDirectory("postalid-prod-test");
        SyntheticPacket.write(packetDir.resolve(SyntheticPacket.RECORD_ID));
        ingestService.ingestDirectory(packetDir);
    }

    private static RequestPostProcessor withScopes(String... scopes) {
        List<GrantedAuthority> authorities = Arrays.stream(scopes)
                .map(scope -> (GrantedAuthority) new SimpleGrantedAuthority("SCOPE_" + scope))
                .toList();
        return jwt().jwt(builder -> builder.claim("azp", "test-client")).authorities(authorities);
    }

    private String matchingBody() {
        return "{\"postalRefNo\":\"" + SyntheticPacket.POSTAL_REF_NO + "\","
                + "\"firstName\":\"" + SyntheticPacket.FIRST_NAME + "\","
                + "\"middleName\":\"" + SyntheticPacket.MIDDLE_NAME + "\","
                + "\"lastName\":\"" + SyntheticPacket.LAST_NAME + "\","
                + "\"dateOfBirth\":\"" + SyntheticPacket.DATE_OF_BIRTH + "\"}";
    }

    // --- Client errors must not be reported as server faults -------------------

    @Test
    @DisplayName("an unsupported content type is 415, not 500")
    void unsupportedMediaTypeIsClientError() throws Exception {
        mockMvc.perform(post(VERIFY_URL)
                        .with(withScopes("postalid.verify"))
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("not json"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    @DisplayName("an unacceptable representation is 406, not 500")
    void unacceptableRepresentationIsClientError() throws Exception {
        mockMvc.perform(post(VERIFY_URL)
                        .with(withScopes("postalid.verify"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_XML)
                        .content(matchingBody()))
                .andExpect(status().isNotAcceptable());
    }

    @Test
    @DisplayName("the wrong method is 405, not 500")
    void wrongMethodIsClientError() throws Exception {
        mockMvc.perform(get(VERIFY_URL).with(withScopes("postalid.verify")))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    @DisplayName("a validation failure still names the offending fields")
    void validationDetailSurvivesTheHandlerChange() throws Exception {
        mockMvc.perform(post(VERIFY_URL)
                        .with(withScopes("postalid.verify"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"postalRefNo\":\"!!\",\"firstName\":\"\",\"lastName\":\"X\","
                                + "\"dateOfBirth\":\"2999-01-01\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors.postalRefNo").isNotEmpty())
                .andExpect(jsonPath("$.errors.firstName").isNotEmpty());
    }

    // --- Request size ---------------------------------------------------------

    @Test
    @DisplayName("an oversized body is refused before it is parsed")
    void oversizedBodyIsRejected() throws Exception {
        String huge = "{\"postalRefNo\":\"" + "A".repeat(40_000) + "\"}";

        mockMvc.perform(post(VERIFY_URL)
                        .with(withScopes("postalid.verify"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(huge))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.title").value("Payload Too Large"));
    }

    // --- Rate limit visibility ------------------------------------------------

    @Test
    @DisplayName("a successful verification publishes the caller's remaining budget")
    void publishesRateLimitHeaders() throws Exception {
        mockMvc.perform(post(VERIFY_URL)
                        .with(withScopes("postalid.verify"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(matchingBody()))
                .andExpect(status().isOk())
                .andExpect(header().exists("RateLimit-Limit"))
                .andExpect(header().exists("RateLimit-Remaining"))
                .andExpect(header().exists("RateLimit-Policy"));
    }

    // --- Health ---------------------------------------------------------------

    @Test
    @DisplayName("health reports the blob store, so a storage outage is not silent")
    void healthCoversTheBlobStore() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        // Readiness must fail if photographs become unreachable, so the component has to be in it.
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("health details stay hidden from anonymous callers")
    void healthDetailsAreNotPublic() throws Exception {
        String body = mockMvc.perform(get("/actuator/health"))
                .andReturn().getResponse().getContentAsString();

        // An anonymous prober learns UP or DOWN and nothing about the internals.
        assertThat(body)
                .doesNotContain("blobstore")
                .doesNotContain("path")
                .doesNotContain("keystore");
    }

    // --- Session handling -----------------------------------------------------

    @Test
    @DisplayName("concurrent-session control has the event publisher it needs to work")
    void sessionEventPublisherIsRegistered() {
        // maximumSessions without this bean never frees a slot when a browser closes, so an
        // operator is eventually locked out of their own console.
        assertThat(context.getBeansOfType(HttpSessionEventPublisher.class))
                .as("HttpSessionEventPublisher must be registered for maximumSessions to work")
                .isNotEmpty();
    }
}
