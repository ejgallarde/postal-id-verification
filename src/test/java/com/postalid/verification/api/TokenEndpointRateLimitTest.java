package com.postalid.verification.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The token endpoint's two budgets: requests per address, and failed authentications per client
 * id. Limits are set tiny here so each one can be tripped in a handful of calls; every test uses
 * its own source address so the per-address budget of one does not leak into another.
 */
@SpringBootTest(properties = {
        "postalid.rate-limit.enabled=true",
        "postalid.rate-limit.token-per-ip-per-minute=3",
        "postalid.rate-limit.token-failures-per-client-per-hour=2"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TokenEndpointRateLimitTest {

    private static final String TOKEN_URL = "/oauth2/token";

    @Autowired MockMvc mockMvc;

    private static RequestPostProcessor from(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    private static MockHttpServletRequestBuilder tokenRequest() {
        return post(TOKEN_URL)
                .param("grant_type", "client_credentials")
                .param("scope", "postalid.verify");
    }

    @Test
    @DisplayName("one address is held to its per-minute budget, whatever the credentials")
    void limitsRequestsPerAddress() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(tokenRequest()
                            .with(httpBasic("verify-only-client", "verify-only-secret"))
                            .with(from("10.0.0.1")))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(tokenRequest()
                        .with(httpBasic("verify-only-client", "verify-only-secret"))
                        .with(from("10.0.0.1")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.error").value("too_many_requests"));

        // A different address is unaffected.
        mockMvc.perform(tokenRequest()
                        .with(httpBasic("verify-only-client", "verify-only-secret"))
                        .with(from("10.0.0.2")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("repeated wrong secrets lock that client id, even across addresses, before BCrypt runs")
    void locksAClientIdAfterRepeatedFailures() throws Exception {
        // Two failures from two different addresses: the guesser is spreading out.
        mockMvc.perform(tokenRequest().with(httpBasic("test-client", "guess-1")).with(from("10.0.1.1")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(tokenRequest().with(httpBasic("test-client", "guess-2")).with(from("10.0.1.2")))
                .andExpect(status().isUnauthorized());

        // The id's failure budget is spent, so even the correct secret is refused for now,
        // and from a third address.
        mockMvc.perform(tokenRequest().with(httpBasic("test-client", "test-client-secret")).with(from("10.0.1.3")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));

        // Other clients are untouched.
        mockMvc.perform(tokenRequest()
                        .with(httpBasic("verify-only-client", "verify-only-secret"))
                        .with(from("10.0.1.4")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("client_secret_post requests are keyed by the client_id form field")
    void keysFormPostedCredentialsToo() throws Exception {
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(tokenRequest()
                            .param("client_id", "unregistered-client")
                            .param("client_secret", "nope")
                            .with(from("10.0.2." + (i + 1))))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(tokenRequest()
                        .param("client_id", "unregistered-client")
                        .param("client_secret", "nope")
                        .with(from("10.0.2.9")))
                .andExpect(status().isTooManyRequests());
    }
}
