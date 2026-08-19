package com.postalid.verification.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Makes the H2 console reachable when developing locally.
 *
 * <p>The console needs framing and does not carry a CSRF token, so it cannot live under the
 * application's own chain without weakening it. Giving it a chain of its own, gated on the
 * {@code dev} profile, keeps those concessions from existing at all in any other environment.
 */
@Configuration
@Profile("dev")
public class DevSecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain h2ConsoleFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/h2-console/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable())
                // The console renders itself in a frameset.
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()));
        return http.build();
    }
}
