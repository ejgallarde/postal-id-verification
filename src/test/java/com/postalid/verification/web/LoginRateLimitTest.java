package com.postalid.verification.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The console sign-in budgets, which are what make it safe to expose the console beyond the
 * machine it runs on. Limits are tiny so each trips in a few calls; each test uses its own source
 * address so their per-address budgets stay apart.
 */
@SpringBootTest(properties = {
        "postalid.rate-limit.enabled=true",
        "postalid.rate-limit.login-per-ip-per-minute=3",
        "postalid.rate-limit.login-failures-per-user-per-hour=2",
        "postalid.web.operators.second-operator.password=second-operator-password"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LoginRateLimitTest {

    @Autowired MockMvc mockMvc;

    /** The form the sign-in page submits, with its CSRF token. */
    private static MockHttpServletRequestBuilder signIn(String username, String password) {
        return post("/login").param("username", username).param("password", password).with(csrf());
    }

    private static RequestPostProcessor from(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    @Test
    @DisplayName("one address is held to its per-minute budget of sign-in attempts")
    void limitsAttemptsPerAddress() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(signIn("nobody-" + i, "x").accept(MediaType.TEXT_HTML)
                            .with(from("10.1.0.1")))
                    .andExpect(redirectedUrl("/login?error"));
        }

        mockMvc.perform(signIn("nobody-9", "x").accept(MediaType.TEXT_HTML)
                        .with(from("10.1.0.1")))
                .andExpect(redirectedUrl("/login?throttled"))
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    @DisplayName("repeated wrong passwords lock that account, even with the right password, from any address")
    void locksAnAccountAfterRepeatedFailures() throws Exception {
        mockMvc.perform(signIn("test-operator", "guess-1").with(from("10.1.1.1")))
                .andExpect(redirectedUrl("/login?error"));
        mockMvc.perform(signIn("test-operator", "guess-2").with(from("10.1.1.2")))
                .andExpect(redirectedUrl("/login?error"));

        // A non-browser caller gets a plain 429; the password is never checked.
        mockMvc.perform(signIn("test-operator", "test-operator-password")
                        .with(from("10.1.1.3")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    @DisplayName("one mistyped password does not lock an account, and successes never count against it")
    void successfulSignInIsUnaffected() throws Exception {
        // Its own account: the lockout test above spends test-operator's budget, and test order
        // is not fixed.
        mockMvc.perform(signIn("second-operator", "typo").with(from("10.1.2.1")))
                .andExpect(redirectedUrl("/login?error"));

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(signIn("second-operator", "second-operator-password").with(from("10.1.2." + (i + 2))))
                    .andExpect(redirectedUrl("/"));
        }
    }
}
